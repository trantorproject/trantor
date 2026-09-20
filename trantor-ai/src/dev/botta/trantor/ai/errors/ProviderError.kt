package dev.botta.trantor.ai.errors

open class ProviderError(
    val provider: String,
    message: String,
    val status: Int? = null,
    val code: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
): AIError(message, cause)
