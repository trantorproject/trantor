package dev.botta.trantor.ai.testing

import dev.botta.trantor.ai.history.Compacted
import dev.botta.trantor.ai.history.Compactor
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.FinishReasons
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.TextPart
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps the last [keep] messages of a conversation behind a summary that says "Resumen", or does nothing, or fails,
 * and remembers every conversation it was asked to compact.
 */
class FakeCompactor(
    var keep: Int = 2,
    var error: Throwable? = null,
    var nothingToDo: Boolean = false,
    usage: Usage = Usage(inputTokens = 50, outputTokens = 10),
): Compactor {
    val conversations = mutableListOf<List<Message>>()

    private val response = ChatResponse(
        listOf(TextPart("Resumen")),
        FinishReasons.Stop,
        ResponseInfo(model = "summarizer", provider = "fake", latency = 1.milliseconds),
        usage = usage,
    )

    override fun compact(conversation: List<Message>, options: CallOptions): Compacted? {
        conversations.add(conversation)
        error?.let { throw it }
        if (nothingToDo) return null

        val summary = Message.Summary("Resumen")

        return Compacted(listOf(summary) + conversation.takeLast(keep), summary, response)
    }
}
