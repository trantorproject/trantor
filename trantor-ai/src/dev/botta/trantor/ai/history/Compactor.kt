package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.primitives.logging.getLogger

/**
 * Replaces the old part of a conversation with a [summary][Message.Summary] of it, so that what is kept and what is
 * sent do not grow without end. Unlike a [ContextPolicy], which only changes what one call sees, it changes the
 * conversation itself: what comes back is what is kept from then on.
 *
 * [SummaryCompactor] asks any model for the summary.
 */
interface Compactor {
    /** [conversation] with its old part summarized, or null when it has no old part to summarize. */
    fun compact(conversation: List<Message>, options: CallOptions = CallOptions()): Compacted?
}

/**
 * A conversation with its old part in a [summary]. [response] is the call that wrote it, whose usage and cost are
 * of the run that asked for it.
 */
class Compacted(val conversation: List<Message>, val summary: Message.Summary, val response: ChatResponse)

/**
 * When a run compacts the conversation it keeps: once it ended well, before it is kept, if its last call to the model
 * went past [afterTokens] — its input, with what came from the cache, and its output, which is how big the next call
 * starts. Set on a run with `compaction(compactor, afterTokens)`.
 *
 * The conversation compacted is the one the run keeps: what its session had, or the history it was given, and what
 * it added; never the instructions of an agent. A run with a session keeps it with [Session.replace]; one without
 * gives it in [RunResult.compacted][dev.botta.trantor.ai.generation.RunResult.compacted], for the application to
 * keep.
 *
 * A compaction that fails does not fail the run, which ended well: the run keeps its conversation as always, and says
 * why in a warning. The next run tries again.
 */
class Compaction(val compactor: Compactor, val afterTokens: Int) {
    init {
        require(afterTokens >= 0) { "A compaction starts at no fewer than 0 tokens, not $afterTokens" }
    }

    /**
     * [result] with [conversation] compacted, when its last call went past [afterTokens]; as it was otherwise. A run
     * that paused is not over, so it is compacted once it ends.
     */
    internal fun after(result: RunResult, conversation: List<Message>, options: CallOptions): RunResult {
        if (result.paused) return result

        val usage = result.response.usage
        val tokens = (usage.inputTokens ?: return result) + (usage.outputTokens ?: 0)

        if (tokens < afterTokens) return result

        return try {
            compactor.compact(conversation, options)?.let { result.copy(compacted = it) } ?: result
        } catch (e: Exception) {
            logger.warn("The conversation was not compacted", e)
            result.copy(compactionWarnings = listOf(ModelWarning("$NOT_COMPACTED: ${e.message}")))
        }
    }

    private companion object {
        const val NOT_COMPACTED = "The conversation was not compacted, and was kept as it was"

        val logger = getLogger(Compaction::class.java.name)
    }
}
