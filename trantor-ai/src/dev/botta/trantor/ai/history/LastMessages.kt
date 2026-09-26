package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.chat.Message

/**
 * Sends at most the last [max] messages of the conversation.
 *
 * It takes the old ones out [step] at a time, not one by one: a window that moves with every message changes what
 * goes first on every call, and the cache of the provider with it. So the start of what is sent stays the same until
 * the conversation grows [step] messages more, and the calls in between read it from the cache.
 *
 * What is sent starts at something the user said, never at the result of a tool or in the middle of a call and its
 * result. When there is nothing the user said after the cut — one long run of tools — it starts at the last thing
 * they said before it, and sends more than [max].
 */
class LastMessages(private val max: Int, private val step: Int = maxOf(1, max / 2)): ContextPolicy {
    init {
        require(max >= 1) { "LastMessages sends at least one message, not $max" }
        require(step in 1..max) { "LastMessages takes out between 1 and $max messages at a time, not $step" }
    }

    override fun project(messages: List<Message>, run: RunContext): List<Message> {
        if (messages.size <= max) return messages

        val over = messages.size - max
        // The fewest whole steps that bring it within the limit
        val cut = (over + step - 1) / step * step
        val start = (cut until messages.size).firstOrNull { messages[it] is Message.User }
            ?: (cut - 1 downTo 0).firstOrNull { messages[it] is Message.User }
            ?: return messages

        return messages.drop(start)
    }
}
