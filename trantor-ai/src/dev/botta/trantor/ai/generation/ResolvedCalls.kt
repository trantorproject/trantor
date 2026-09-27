package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart

/**
 * The calls a conversation left waiting for approval, answered by the run that picked it up before it called the
 * model: the approved ones ran, and the others were answered as not approved.
 */
data class ResolvedCalls(
    /** In the order of the calls, as the model reads them. */
    val results: List<ToolResultPart>,
    /** The approved calls that failed, with their exception. */
    val failures: List<ToolFailure> = emptyList(),
    /** The calls that got no decision, which were answered as not approved. */
    val warnings: List<ModelWarning> = emptyList(),
    /** The runs of a model the approved calls made to answer, by the id of their call, which the run counts. */
    val toolRuns: Map<String, RunResult> = emptyMap(),
) {
    /**
     * [conversation] with the results right after the answer that made the calls and the results it already had,
     * which is where the providers take them: before a message the user wrote after the run paused. Never before
     * [from], the part of [conversation] that is already kept and cannot change.
     */
    internal fun into(conversation: List<Message>, from: Int = 0): List<Message> {
        val ids = results.map { it.callId }.toSet()
        val answer = conversation.indexOfLast { message ->
            message is Message.Assistant && message.parts.any { it is ToolCallPart && it.callId in ids }
        }
        var at = if (answer < 0) conversation.size else answer + 1

        while (at < conversation.size && conversation[at] is Message.Tool) at++

        return conversation.toMutableList().apply { add(maxOf(at, from), Message.Tool(results)) }
    }
}
