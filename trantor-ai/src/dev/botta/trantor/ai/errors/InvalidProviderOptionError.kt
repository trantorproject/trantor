package dev.botta.trantor.ai.errors

/** A provider option the adapter cannot apply, like a raw key that would overwrite what the adapter itself built. */
class InvalidProviderOptionError(
    val provider: String,
    val option: String,
    message: String,
    cause: Throwable? = null,
): AIError(message, cause)
