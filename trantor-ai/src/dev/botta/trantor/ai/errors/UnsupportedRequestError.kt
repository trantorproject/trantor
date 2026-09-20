package dev.botta.trantor.ai.errors

import dev.botta.trantor.ai.models.ModelWarning

/**
 * The adapter could not honor part of the request and `ChatSettings.failOnWarnings` asked for that to fail
 * instead of ending up as a warning in the response.
 */
class UnsupportedRequestError(
    val provider: String,
    val warnings: List<ModelWarning>,
): AIError("$provider could not honor the request: " + warnings.joinToString("; ") { it.message })
