package dev.botta.trantor.ai.tools.search

import dev.botta.trantor.ai.tools.FunctionToolSpec

/**
 * Finds, among the searchable tools of a run, the ones that answer what the model searched for: by their words
 * ([KeywordToolSearcher], unless the run is given another with `toolSearcher(...)`), with embeddings, a search engine
 * of the application, or anything else.
 *
 * The model searches with `search_tools`. On a model whose provider searches tools, that goes as the provider's own
 * search, run by the application: Anthropic loads the tools found from references to them, and OpenAI from their
 * definitions, so the model sees only those and the cache holds. [ProviderToolSearcher] asks for the provider's own
 * search instead. See [Tool search](https://github.com/nbottarini/trantor/blob/main/docs/trantor-ai.md#tool-search).
 */
fun interface ToolSearcher {
    /**
     * The tools of [tools] that answer [query], best first. The model reads their names and descriptions, so a few
     * good ones serve it better than many: the search by words gives five at most.
     */
    fun search(query: String, tools: List<FunctionToolSpec>): List<FunctionToolSpec>
}

/**
 * The search by words of Trantor, which a run uses unless it is given another: the words of the query in the names,
 * the descriptions and the arguments of the tools, with no embeddings and no call to a model.
 */
object KeywordToolSearcher: ToolSearcher {
    override fun search(query: String, tools: List<FunctionToolSpec>) = ToolSearch(tools).search(query)
}

/**
 * Asks for the provider's own search where the model has one: the BM25 tool search of Anthropic and the hosted
 * `tool_search` of OpenAI, which search and call in the same answer, a step less than a search of Trantor. What they
 * find is theirs to decide, and on OpenAI the model still sees the name and description of every tool to search for,
 * which saves little. Where the model has none, the tools are searched by their words.
 */
object ProviderToolSearcher: ToolSearcher {
    override fun search(query: String, tools: List<FunctionToolSpec>) = KeywordToolSearcher.search(query, tools)
}
