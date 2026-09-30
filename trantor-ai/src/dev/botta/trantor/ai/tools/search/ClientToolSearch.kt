package dev.botta.trantor.ai.tools.search

import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.ToolSpec

/**
 * The search of the application over the deferred tools of a request ([FunctionToolSpec.searchesTools]), as an
 * adapter whose provider loads what the client found sees it: which tool it is, and which deferred tools an answer of
 * it names. The answer is the one `search_tools` gives on every route, so a conversation reads the same whoever
 * searched; only the adapter turns it into what its provider loads the tools from.
 */
internal class ClientToolSearch(tools: List<ToolSpec>) {
    private val functions = tools.filterIsInstance<FunctionToolSpec>()
    private val deferred = functions.filter { it.deferLoading }.associateBy { it.name }

    /** The search, when the request has one. */
    val spec = functions.firstOrNull { it.searchesTools }

    /** Whether the tool called [toolName] is the search. */
    fun isSearch(toolName: String) = spec != null && spec.name == toolName

    /**
     * The deferred tools that [part], an answer of the search, names, in its order; none when the search failed. A
     * name the request has no deferred tool for is left out: the provider answers 400 to a tool it cannot load.
     */
    fun found(part: ToolResultPart): List<FunctionToolSpec> =
        if (part.isError) emptyList() else SearchToolsTool.namesIn(part.output).mapNotNull { deferred[it] }
}
