package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.errors.AIError

/**
 * A run got a decision about a call that is not waiting for approval: the conversation has no such call, or it was
 * already answered, which is what a decision sent twice finds. Nothing of the run happened.
 */
class NoPendingCallError(val callId: String, message: String): AIError(message)
