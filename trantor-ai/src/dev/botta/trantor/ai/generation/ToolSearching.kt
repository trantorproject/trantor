package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.search.KeywordToolSearcher
import dev.botta.trantor.ai.tools.search.ProviderToolSearcher
import dev.botta.trantor.ai.tools.search.SearchToolsTool

/** Who searches the tools of a step that has tools to search for. */
internal enum class ToolSearchRoutes {
    /**
     * The provider, with its own search, when the run asks for it ([ProviderToolSearcher]): the tools go deferred,
     * and nothing of Trantor searches.
     */
    Provider,

    /**
     * The searcher of the run, as the provider's own search run by the client: the tools go deferred, and
     * `search_tools` goes marked as the search, so that the provider loads what it finds.
     */
    Client,

    /** The loop: `search_tools`, and the ones it found told to the model like any other tool from the next step. */
    Loop,
    ;

    companion object {
        /** Null when the step has nothing to search for. */
        fun of(setup: StepSetup): ToolSearchRoutes? = when {
            setup.searchable.isEmpty() -> null
            !setup.model.searchesTools -> Loop
            setup.searcher == ProviderToolSearcher -> Provider
            else -> Client
        }
    }
}

/**
 * What a step with tools to search for adds to the ones it always has. Where the provider loads the tools
 * ([ToolSearchRoutes.Provider] and [ToolSearchRoutes.Client]), all of them, since the model can call any it found;
 * where the loop searches, the tool to search with and the ones the conversation says it found.
 */
internal fun searching(setup: StepSetup): List<Tool<*>> = when (ToolSearchRoutes.of(setup)) {
    null -> emptyList()
    ToolSearchRoutes.Provider -> setup.searchable
    ToolSearchRoutes.Client -> listOf(searchTool(setup)) + setup.searchable
    ToolSearchRoutes.Loop -> {
        val found = SearchToolsTool.found(setup.request.messages)
        listOf(searchTool(setup)) + setup.searchable.filter { it.name in found }
    }
}

private fun searchTool(setup: StepSetup) = SearchToolsTool(setup.searchable, setup.searcher ?: KeywordToolSearcher)
