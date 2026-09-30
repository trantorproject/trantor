package dev.botta.trantor.ai.tools.search

import dev.botta.trantor.ai.tools.FunctionToolSpec

/**
 * Finds tools by the words of what the model asks for, in their names, their descriptions and their arguments: the
 * fields the tool search of Anthropic looks in. Names are split into words however they are written (`getWeather`,
 * `github_create_issue`), and a word in the name counts more than one anywhere else. A word matches another that
 * starts like it, so `issues` finds `issue`. No embeddings and no call to a model, as the local search of the Vercel
 * AI SDK and Pydantic AI.
 */
internal class ToolSearch(private val specs: List<FunctionToolSpec>) {
    private val indexed = specs.map { Indexed(it) }

    /** The tools that match [query], best first and at most [MAX_RESULTS]; the order given when they are as good. */
    fun search(query: String): List<FunctionToolSpec> {
        val terms = words(query).distinct()
        if (terms.isEmpty()) return emptyList()

        return indexed.map { it to it.score(terms) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_RESULTS)
            .map { (tool, _) -> tool.spec }
    }

    private class Indexed(val spec: FunctionToolSpec) {
        private val name = words(spec.name)
        private val rest = words(spec.description.orEmpty()) + argumentWords()

        fun score(terms: List<String>) = terms.sumOf { term ->
            when {
                name.any { matches(it, term) } -> NAME_WEIGHT
                rest.any { matches(it, term) } -> 1
                else -> 0
            }
        }

        private fun argumentWords(): List<String> {
            val properties = spec.parameters["properties"]?.asObject() ?: return emptyList()

            return properties.flatMap { (argument, schema) ->
                words(argument) + words(schema.asObject()?.get("description")?.asString().orEmpty())
            }
        }
    }

    companion object {
        const val MAX_RESULTS = 5
        private const val NAME_WEIGHT = 3

        /** A word that starts like another counts as it, but not a short one: `or` is not `order`. */
        private const val SHORTEST_PREFIX = 4

        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
        private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

        private fun words(text: String) = text.split(SEPARATORS)
            .flatMap { it.split(CAMEL) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

        private fun matches(word: String, term: String) = word == term ||
            (minOf(word.length, term.length) >= SHORTEST_PREFIX && (word.startsWith(term) || term.startsWith(word)))
    }
}
