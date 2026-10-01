package dev.botta.trantor.ai.errors

/** The provider turned the credentials down: a key that is missing, wrong or revoked. Trying again does not help. */
class AuthenticationError(
    provider: String,
    message: String = "Invalid or missing credentials for $provider",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = false, cause = cause)
