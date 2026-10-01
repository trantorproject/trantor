package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonObject

/**
 * What a model is told about a tool: one the application runs ([FunctionToolSpec]) or one the provider runs on its
 * side ([ProviderToolSpec]).
 */
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
     * Told to the provider as one the model does not see until the search of the request ([searchesTools]) finds
     * it, on a model that loads deferred tools
     * ([ChatModel.loadsDeferredTools][dev.botta.trantor.ai.models.chat.ChatModel.loadsDeferredTools]). Anywhere else
     * it goes up front, with a warning.
     */
    val deferLoading: Boolean = false,
    /**
     * The search of the application over the tools that are [deferLoading]: the provider takes it as its own search,
     * run by the application, and loads the tools its answers name. The tool loop marks `search_tools` with it.
     */
    val searchesTools: Boolean = false,
): ToolSpec

/** A tool the provider runs on its side, like "openai.web_search". */
data class ProviderToolSpec(override val name: String, val args: JsonObject): ToolSpec
