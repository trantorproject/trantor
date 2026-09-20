package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonObject

sealed interface ToolSpec {
    val name: String
}

/** A tool the application runs. [parameters] is a JSON Schema. */
data class FunctionToolSpec(
    override val name: String,
    val description: String? = null,
    val parameters: JsonObject,
    val strict: Boolean = true,
): ToolSpec

/** A tool the provider runs on its side, like "openai.web_search". */
data class ProviderToolSpec(override val name: String, val args: JsonObject): ToolSpec
