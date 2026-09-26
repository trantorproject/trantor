package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.errors.NoSummaryWrittenError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.FinishReasons
import dev.botta.trantor.ai.models.chat.Message
import io.opentelemetry.api.OpenTelemetry
import dev.botta.trantor.ai.telemetry.GenAITelemetry

/**
 * A [Compactor] that asks [model] for the summary: any model of any provider, a cheaper one included, which is
 * what Microsoft, LangChain and ADK do.
 *
 * - **What stays:** the system messages the conversation starts with, and its last [keepTurns] turns, as they are.
 *   A turn starts at something the user said, so a call is never cut from its result.
 * - **What is summarized:** everything between them, the summary from a compaction before included, so the new
 *   summary covers the old one and what came after it.
 * - **What the model reads:** [instructions], and the old part told line by line — who said what, called what and
 *   got what — as something the user tells. Not the turns themselves: they carry calls to tools this call does not
 *   have, which Anthropic rejects, and reasoning signed for another model. The price is that it reads all of it
 *   without the cache of the conversation; a summary of the provider's own reads it from the cache.
 *
 * With an [openTelemetry] that exports, its call is a `chat` span, inside the span of the run that compacts.
 *
 * A model that writes no summary, or one cut short, fails the compaction with [NoSummaryWrittenError], and the
 * conversation stays as it was. What is summarized is lost but for what the summary kept, which is why the last
 * turns stay whole and the instructions can say what matters to the application.
 */
class SummaryCompactor(
    private val model: ChatModel,
    private val keepTurns: Int = 2,
    private val instructions: String = DEFAULT_INSTRUCTIONS,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): Compactor {
    private val telemetry = GenAITelemetry(openTelemetry)

    init {
        require(keepTurns >= 0) { "SummaryCompactor keeps no fewer than 0 turns, not $keepTurns" }
    }

    override fun compact(conversation: List<Message>, options: CallOptions): Compacted? {
        val system = conversation.takeWhile { it is Message.System }
        val rest = conversation.drop(system.size)
        val kept = keptFrom(rest) ?: return null
        val old = rest.take(kept)

        // A summary alone is already as short as it gets
        if (old.all { it is Message.Summary }) return null

        val request = ChatRequest(Message.system(instructions), Message.user(Transcript.of(old).joinToString("\n")))
        val response = telemetry.chat(model, request, null, null) { model.generate(request, options) }

        if (response.text.isBlank() || response.finishReason in CUT_SHORT) {
            throw NoSummaryWrittenError(
                "${model.modelId} wrote no summary of the conversation (it finished by ${response.finishReason}), " +
                    "so it was left as it was",
                response,
            )
        }

        val summary = Message.Summary(response.text.trim())

        return Compacted(system + summary + rest.drop(kept), summary, response)
    }

    /** Where the turns that stay start, or null when there are not more turns than the ones that stay. */
    private fun keptFrom(messages: List<Message>): Int? {
        if (keepTurns == 0) return messages.size

        val turns = messages.indices.filter { messages[it] is Message.User }

        return turns.getOrNull(turns.size - keepTurns)
    }

    companion object {
        const val DEFAULT_INSTRUCTIONS = "Summarize the conversation below so that it can go on without it. Keep " +
            "every fact, name, number, date and decision, what the user asked for and what is still pending, and " +
            "what the tools returned that still matters. Leave out greetings and small talk. Write it in the " +
            "language of the conversation, and answer with the summary alone, without a title or headings."

        private val CUT_SHORT =
            setOf(FinishReasons.Length, FinishReasons.ContentFilter, FinishReasons.Refusal, FinishReasons.Error)
    }
}
