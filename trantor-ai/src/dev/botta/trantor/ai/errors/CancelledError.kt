package dev.botta.trantor.ai.errors

/**
 * The call or the run was cancelled, by its cancellation or by interrupting its thread. What was done before it
 * stays done.
 */
class CancelledError(message: String = "The call was cancelled", cause: Throwable? = null): AIError(message, cause)
