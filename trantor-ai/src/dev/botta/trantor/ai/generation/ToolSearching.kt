package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.search.SearchToolsTool

/**
 * What a step with tools to search for adds to the ones it always has.
 *
 * On a model whose provider searches ([dev.botta.trantor.ai.models.chat.ChatModel.searchesTools]), all of them: the
 * provider is told about them deferred, finds the ones the model needs and loads them, and the model can call any it
 * found. Elsewhere, the tool to search with and the ones the conversation says it found.
 */
internal fun searching(setup: StepSetup): List<Tool<*>> {
    if (setup.searchable.isEmpty()) return emptyList()
    if (searchesOnItsOwn(setup)) return setup.searchable

    val found = SearchToolsTool.found(setup.request.messages)

    return listOf(SearchToolsTool(setup.searchable)) + setup.searchable.filter { it.name in found }
}

/** Whether the provider of the step searches its tools to search for, which it is told about deferred. */
internal fun searchesOnItsOwn(setup: StepSetup) = setup.searchable.isNotEmpty() && setup.model.searchesTools
