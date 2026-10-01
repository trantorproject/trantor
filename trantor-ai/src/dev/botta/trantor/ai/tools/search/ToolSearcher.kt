package dev.botta.trantor.ai.tools.search

import dev.botta.trantor.ai.tools.FunctionToolSpec

/**
 * Finds, among the searchable tools of a run, the ones that answer what the model searched for: by their words
 * ([KeywordToolSearcher], unless the run is given another with `toolSearcher(...)`), with embeddings, a search engine
 * of the application, or anything else.
 *
 * The model searches with `search_tools`. On a model whose provider loads deferred tools, the provider loads the ones
 * found — Anthropic from references to them, OpenAI from their definitions — so the model sees only those and the
 * cache holds. See [Tool search](https://github.com/nbottarini/trantor/blob/main/docs/trantor-ai/tools.md#tool-search).
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
