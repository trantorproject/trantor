package dev.botta.trantor.ai.errors

class ProviderUnavailableError(
    provider: String,
    message: String = "$provider is unavailable",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = true, cause = cause)
