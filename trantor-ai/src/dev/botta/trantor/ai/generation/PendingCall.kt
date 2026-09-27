package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.ToolCallPart

/**
 * A call that waits for a person to approve it: its tool asked for approval, so it did not run and the run ended
 * paused. It stays in the conversation without its result, which is how the run that picks it up finds it.
 */
data class PendingCall(
    /** The call as the model made it: its id, its tool and its args, which are what the person approves. */
    val call: ToolCallPart,
    /** The agent that made the call, which is who picks the run up. Null in a generation. */
    val agent: String?,
    /**
     * Why it waits, when a check asked for approval and said why, like a tool guardrail. Null when its tool asked,
     * which says no more than that it does.
     */
    val reason: String? = null,
)
