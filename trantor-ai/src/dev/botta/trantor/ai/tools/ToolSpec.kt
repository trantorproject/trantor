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
    /**
     * Told to a provider that searches tools ([ChatModel.searchesTools][dev.botta.trantor.ai.models.chat.ChatModel]
     * .searchesTools) as one the model finds when it needs it, instead of up front.
     */
    val deferLoading: Boolean = false,
    /**
     * The search of the application over the tools that are [deferLoading]. A provider that searches tools takes it
     * as its own search, run by the application, and loads the tools its answers name; elsewhere it is a tool like
     * any other. The tool loop marks `search_tools` with it on a model that searches tools, unless the run asks for
     * the provider's own search.
     */
    val searchesTools: Boolean = false,
): ToolSpec

/** A tool the provider runs on its side, like "openai.web_search". */
data class ProviderToolSpec(override val name: String, val args: JsonObject): ToolSpec
