package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ToolCallPart

/**
 * The calls a conversation left waiting for approval: those of its last answer without a result after it, which is
 * how a run that paused leaves it. Any other call without its result is a pair broken, which [ToolPairs] takes out.
 */
internal object WaitingCalls {
    fun of(messages: List<Message>): List<ToolCallPart> = waiting(messages)?.second.orEmpty()

    /** The agent that made them, which is who picks the run up. Null when none wait, or a generation made them. */
    fun agentOf(messages: List<Message>): String? = waiting(messages)?.first?.agent

    private fun waiting(messages: List<Message>): Pair<Message.Assistant, List<ToolCallPart>>? {
        val last = messages.indexOfLast { it is Message.Assistant }.takeIf { it >= 0 } ?: return null
        val answer = messages[last] as Message.Assistant
        val answered = messages.drop(last + 1).filterIsInstance<Message.Tool>().flatMap { it.results }.map { it.callId }
        val calls = answer.parts
            .filterIsInstance<ToolCallPart>()
            .filter { !it.providerExecuted && it.callId !in answered }

        return calls.takeIf { it.isNotEmpty() }?.let { answer to it }
    }
}

/**
 * A [NextStep] told when an approved call the run answered before its first step handed the conversation over, so
 * that the first step goes out as [to]. Only the runner of the agents is one.
 */
internal interface HandsOver {
    fun handedOver(to: String)
}
