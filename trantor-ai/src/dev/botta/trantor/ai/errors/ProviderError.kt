package dev.botta.trantor.ai.errors

open class ProviderError(
    val provider: String,
    message: String,
    val status: Int? = null,
    val code: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
    // Which parameter of the request the provider complained about, when it says so. Its message often doesn't.
    val parameter: String? = null,
): AIError(message, cause)
