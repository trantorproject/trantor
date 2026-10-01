package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.errors.AIError
import dev.botta.trantor.ai.models.chat.FinishReasons

/** The model did not answer with the object asked for: it refused, ran out of tokens or wrote something else. */
class NoObjectGeneratedError(
    message: String,
    val finishReason: FinishReasons,
    val refusal: String? = null,
    val text: String = "",
    cause: Throwable? = null,
): AIError(message, cause)
