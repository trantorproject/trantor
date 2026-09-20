package dev.botta.trantor.ai.errors

import dev.botta.trantor.ai.models.chat.FinishReasons

/** The model didn't answer with the object that was asked for: it refused, ran out of tokens or wrote something else. */
class NoObjectGeneratedError(
    message: String,
    val finishReason: FinishReasons,
    val refusal: String? = null,
    val text: String = "",
    cause: Throwable? = null,
): AIError(message, cause)
