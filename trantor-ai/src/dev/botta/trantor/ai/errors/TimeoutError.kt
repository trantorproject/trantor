package dev.botta.trantor.ai.errors

class TimeoutError(message: String = "The call timed out", cause: Throwable? = null): AIError(message, cause)
