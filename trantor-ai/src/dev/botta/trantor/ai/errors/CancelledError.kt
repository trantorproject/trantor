package dev.botta.trantor.ai.errors

class CancelledError(message: String = "The call was cancelled", cause: Throwable? = null): AIError(message, cause)
