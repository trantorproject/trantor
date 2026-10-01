package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.search.KeywordToolSearcher
import dev.botta.trantor.ai.tools.search.SearchToolsTool

/**
 * What a step with tools to search for adds to the ones it always has: `search_tools`, with the searcher of the run.
 *
 * On a model that loads deferred tools ([dev.botta.trantor.ai.models.chat.ChatModel.loadsDeferredTools]) all the
 * searchable ones go too, deferred, and the provider loads the ones the search finds; elsewhere only the ones the
 * conversation says were found, told to the model like any other.
 */
internal fun searching(setup: StepSetup): List<Tool<*>> {
    if (setup.searchable.isEmpty()) return emptyList()

    val search = SearchToolsTool(setup.searchable, setup.searcher ?: KeywordToolSearcher)
    if (providerLoads(setup)) return listOf(search) + setup.searchable

    val found = SearchToolsTool.found(setup.request.messages)

    return listOf(search) + setup.searchable.filter { it.name in found }
}

/** Whether the provider of the step loads the tools the search finds, which then go to it deferred. */
internal fun providerLoads(setup: StepSetup) = setup.searchable.isNotEmpty() && setup.model.loadsDeferredTools
