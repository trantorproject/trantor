package dev.botta.trantor.ai.errors

/**
 * The request does not fit in the context window of the model. A context policy or a compaction makes it fit;
 * trying again as it is does not.
 */
class ContextLengthExceededError(
    provider: String,
    message: String = "The request exceeds the context window of the model",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = false, cause = cause)
