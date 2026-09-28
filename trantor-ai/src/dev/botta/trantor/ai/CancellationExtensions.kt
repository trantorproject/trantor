package dev.botta.trantor.ai

import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.primitives.Cancellation

/**
 * Fails with [CancelledError] when [Cancellation] was cancelled. It lives here and not in the token because the error
 * is an error of AI, which is caught along with the others of a call.
 */
fun Cancellation.throwIfCancelled() {
    if (isCancelled) throw CancelledError()
}
