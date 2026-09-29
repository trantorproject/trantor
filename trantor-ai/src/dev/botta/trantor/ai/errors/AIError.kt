package dev.botta.trantor.ai.errors

open class AIError(message: String, cause: Throwable? = null): RuntimeException(message, cause)
