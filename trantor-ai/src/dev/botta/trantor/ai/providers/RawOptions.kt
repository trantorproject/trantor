package dev.botta.trantor.ai.providers

import dev.botta.json.values.JsonObject

/**
 * Options written as the provider's own JSON, merged into the body of the request.
 *
 * They are the escape hatch for a parameter that came out today and Trantor doesn't have typed yet. A key that the
 * adapter already filled in is a conflict and fails: the request the model receives should never be something the
 * caller did not mean to send.
 */
data class RawOptions(override val provider: String, val values: JsonObject): ProviderOption
