package dev.botta.trantor.ai.generation

/**
 * What a person decided about a call that waits for approval, in [RunResult.pending] of the run that paused. The run
 * that picks the conversation up gets it with `decisions(...)`, and answers the call before calling the model.
 *
 * It names the call by its id alone: the call itself, its tool and its args, is read from the conversation, which the
 * application keeps on its side, and not from whoever decides.
 */
sealed interface Decision {
    val callId: String
}

/**
 * The call runs, with the tools and the hooks of the first step of the run that picks it up, without asking for
 * approval again, and the model reads its result.
 */
data class Approve(override val callId: String): Decision

/**
 * The call does not run, and the model reads [message] as its error. Without one, it reads that the call was not
 * approved, that it should not call it again and that it should tell the user: told only that it was not approved,
 * o4-mini asked for it again right away when the user had asked for it.
 */
data class Reject(override val callId: String, val message: String? = null): Decision
