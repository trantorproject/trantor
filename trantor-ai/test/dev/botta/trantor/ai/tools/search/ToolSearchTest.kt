@file:Suppress("ClassName")

package dev.botta.trantor.ai.tools.search

import dev.botta.json.Json
import dev.botta.trantor.ai.tools.FunctionToolSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The search behind `search_tools`, by the words of what the model asks for. */
class ToolSearchTest {
    @Test
    fun `finds a tool by the words of its name, however it is written`() {
        assertThat(search.search("weather").names()).containsExactly("getWeather")
        assertThat(search.search("create issue").names()).first().isEqualTo("github_create_issue")
    }

    @Test
    fun `a name written in camel case is words too, even when nothing else says them`() {
        val search = ToolSearch(listOf(spec("getInvoice", "Returns a document"), spec("sendMail", "Sends a message")))

        assertThat(search.search("invoice").names()).containsExactly("getInvoice")
    }

    @Test
    fun `and by the words of its description`() {
        assertThat(search.search("money back").names()).containsExactly("refund")
    }

    @Test
    fun `and by its arguments, their names and what they say`() {
        assertThat(search.search("repository").names()).containsExactly("github_create_issue")
        assertThat(search.search("tracking").names()).containsExactly("shipment_status")
    }

    @Test
    fun `a word in the name counts more than one in the description`() {
        val search = ToolSearch(listOf(spec("refund", "Gives the money of an order back"), spec("list_orders", "")))

        assertThat(search.search("order").names()).containsExactly("list_orders", "refund")
    }

    @Test
    fun `a word matches another that starts like it, so a plural finds the singular`() {
        assertThat(search.search("issues").names()).first().isEqualTo("github_create_issue")
    }

    @Test
    fun `gives five at most, and the order they were given when they are as good`() {
        val many = (1..8).map { spec("tool_$it", "Searches the catalog, part $it") }

        assertThat(ToolSearch(many).search("catalog").names())
            .containsExactly("tool_1", "tool_2", "tool_3", "tool_4", "tool_5")
    }

    @Test
    fun `finds nothing when no word matches`() {
        assertThat(search.search("poem")).isEmpty()
    }

    private fun List<FunctionToolSpec>.names() = map { it.name }

    private fun spec(name: String, description: String, vararg args: Pair<String, String?>) = FunctionToolSpec(
        name,
        description,
        Json.obj(
            "type" to "object",
            "properties" to Json.obj(
                *args.map { (arg, about) ->
                    arg to Json.obj("type" to "string").apply { about?.let { this["description"] = it } }
                }.toTypedArray(),
            ),
        ),
    )

    private val search = ToolSearch(
        listOf(
            spec("github_create_issue", "Opens an issue", "repository" to "Where it goes", "title" to null),
            spec("getWeather", "The current weather of a city", "city" to null),
            spec("list_orders", "The orders of a customer", "customer" to null),
            spec("refund", "Gives the money of an order back", "order" to "The number of the order"),
            spec("shipment_status", "Where a package is", "code" to "The tracking code"),
        ),
    )
}
