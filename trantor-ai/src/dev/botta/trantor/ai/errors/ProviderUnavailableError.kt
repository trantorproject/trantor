package dev.botta.trantor.ai.errors

/** The provider could not answer: overloaded, down, or out of time on its side. Trying again later can work. */
class ProviderUnavailableError(
    provider: String,
    message: String = "$provider is unavailable",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = true, cause = cause)
