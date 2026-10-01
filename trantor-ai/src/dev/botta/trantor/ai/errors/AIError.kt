package dev.botta.trantor.ai.errors

/**
 * The base of the errors of trantor-ai, so that what a call to a model or a run failed with can be caught in one
 * place.
 */
open class AIError(message: String, cause: Throwable? = null): RuntimeException(message, cause)
