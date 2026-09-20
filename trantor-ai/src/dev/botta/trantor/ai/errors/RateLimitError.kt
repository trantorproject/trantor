package dev.botta.trantor.ai.errors

import kotlin.time.Duration

class RateLimitError(
    provider: String,
    val retryAfter: Duration? = null,
    message: String = "Rate limit exceeded on $provider",
    status: Int? = null,
    code: String? = null,
    cause: Throwable? = null,
): ProviderError(provider, message, status, code, retryable = true, cause = cause)
