package dev.botta.trantor.ai.errors

class ContextLengthExceededError(
    provider: String,
    message: String = "The request exceeds the context window of the model",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = false, cause = cause)
