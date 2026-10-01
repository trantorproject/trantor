package dev.botta.trantor.ai.errors

/** The call took longer than it was given. */
class TimeoutError(message: String = "The call timed out", cause: Throwable? = null): AIError(message, cause)
