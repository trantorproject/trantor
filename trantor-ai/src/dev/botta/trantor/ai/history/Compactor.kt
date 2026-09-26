package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.Message

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
