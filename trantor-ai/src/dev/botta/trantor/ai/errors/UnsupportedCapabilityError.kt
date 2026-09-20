package dev.botta.trantor.ai.errors

class UnsupportedCapabilityError(
    val capability: String,
    message: String = "Capability $capability is not supported",
    cause: Throwable? = null,
): AIError(message, cause)
