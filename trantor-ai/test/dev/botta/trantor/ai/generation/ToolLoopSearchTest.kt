@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.tools.search.ToolSearcher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tools the model searches for instead of being told about them up front, on a model without a tool search of its
 * own: `search_tools` finds them, and the loop adds the ones found to the steps that follow.
 */
class ToolLoopSearchTest {
    @Test
    fun `the model is told about the tools it always has and how to search, not about the ones to search for`() {
        model.answers(listOf(TextPart("Hola")))

        loop().run(ChatRequest("Hola"))

        assertThat(model.requests[0].tools.map { it.name }).containsExactly("getTime", "search_tools")
    }

    @Test
    fun `finds the tools that match what it searched for`() {
        model.answers(listOf(search("weather")), listOf(TextPart("Listo")))

        val result = loop().run(ChatRequest("Llueve?"))

        val found = (result.steps[0].toolResults.single().output as ToolOutput.Json).value.asObject()!!
        assertThat(found["tools"]?.asArray()?.map { it.asObject()?.get("name")?.asString() })
            .containsExactly("getWeather")
    }

    @Test
    fun `finding nothing says what the tools are called, so the model can search again with their words`() {
        model.answers(listOf(search("rastrear paquete")), listOf(TextPart("Listo")))

        val result = loop().run(ChatRequest("Donde esta mi paquete?"))

        val answer = (result.steps[0].toolResults.single().output as ToolOutput.Json).value.asObject()!!
        assertThat(answer["tools"]?.asArray()).isEmpty()
        assertThat(answer["names"]?.asArray()?.map { it.asString() }).containsExactly("getWeather", "refund")
    }

    @Test
    fun `and a tool found is told about in the next step, and runs when the model calls it`() {
        model.answers(listOf(search("weather")), listOf(weatherCall), listOf(TextPart("Llueve")))

        loop().run(ChatRequest("Llueve?"))

        assertThat(model.requests[1].tools.map { it.name }).containsExactly("getTime", "search_tools", "getWeather")
        assertThat(weather.cities).containsExactly("Bariloche")
    }

    @Test
    fun `stays found in the next run of the conversation, which says it found it`() {
        model.answers(listOf(search("weather")), listOf(TextPart("Encontre el clima")), listOf(TextPart("Llueve")))
        val question = Message.user("Llueve?")
        val first = loop().run(ChatRequest(listOf(question)))

        loop().run(ChatRequest(listOf(question) + first.newMessages + Message.user("Y en Bariloche?")))

        assertThat(model.requests[2].tools.map { it.name }).contains("getWeather")
    }

    @Test
    fun `a tool to search for that was not found does not run, as if it did not exist`() {
        model.answers(listOf(weatherCall), listOf(TextPart("Perdon")))

        val result = loop().run(ChatRequest("Llueve?"))

        assertThat(result.steps[0].toolResults.single().isError).isTrue()
        assertThat(weather.cities).isEmpty()
    }

    @Test
    fun `a tool of the application called like the one that searches cannot be told apart from it`() {
        model.answers(listOf(TextPart("Hola")))
        val loop = ToolLoop(model, listOf(time, NamedLikeSearch()), searchableTools = listOf(weather))

        assertThatThrownBy { loop.run(ChatRequest("Hola")) }.isInstanceOf(DuplicateToolError::class.java)
    }

    @Test
    fun `on a model that searches, the search of Trantor goes as its search, and the provider loads what it finds`() {
        model.loadsDeferredTools = true
        model.answers(listOf(TextPart("Hola")))

        loop().run(ChatRequest("Hola"))

        val told = model.requests[0].tools.map { it as FunctionToolSpec }
        assertThat(told.map { Triple(it.name, it.deferLoading, it.searchesTools) }).containsExactly(
            Triple("getTime", false, false),
            Triple("search_tools", false, true),
            Triple("getWeather", true, false),
            Triple("refund", true, false),
        )
    }

    @Test
    fun `a searcher of the application is the one that searches`() {
        model.answers(listOf(search("clima")), listOf(TextPart("Listo")))

        val result = loop(searcher).run(ChatRequest("Llueve?"))

        assertThat(searcher.queries).containsExactly("clima")
        val found = (result.steps[0].toolResults.single().output as ToolOutput.Json).value.asObject()!!
        assertThat(found["tools"]?.asArray()?.map { it.asObject()?.get("name")?.asString() })
            .containsExactly("refund")
    }

    @Test
    fun `and a tool it found runs when the model calls it, since the provider loads it`() {
        model.loadsDeferredTools = true
        model.answers(listOf(search("clima")), listOf(weatherCall), listOf(TextPart("Llueve")))

        loop(searcher).run(ChatRequest("Llueve?"))

        assertThat(searcher.queries).containsExactly("clima")
        assertThat(weather.cities).containsExactly("Bariloche")
    }

    private fun loop(searcher: ToolSearcher? = null) =
        ToolLoop(model, listOf(time), searchableTools = listOf(weather, refund), toolSearcher = searcher)

    private fun search(query: String) = ToolCallPart("call_s", "search_tools", Json.obj("query" to query))

    private val weatherCall = ToolCallPart("call_w", "getWeather", Json.obj("city" to "Bariloche"))

    private val model = FakeChatModel()
    private val time = TimeTool()
    private val weather = WeatherTool()
    private val refund = RefundTool()
    private val searcher = FakeSearcher()

    /** A search of the application, which finds the refund whatever it is asked. */
    class FakeSearcher: ToolSearcher {
        val queries = mutableListOf<String>()

        override fun search(query: String, tools: List<FunctionToolSpec>): List<FunctionToolSpec> {
            queries.add(query)
            return tools.filter { it.name == "refund" }
        }
    }

    class TimeTool: Tool<TimeTool.Args>() {
        override val name = "getTime"
        override val description = "The time"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("12:00")

        class Args
    }

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        val cities = mutableListOf<String>()

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities += args.city
            return ToolResult.text("7 grados")
        }

        data class Args(val city: String)
    }

    class RefundTool: Tool<RefundTool.Args>() {
        override val name = "refund"
        override val description = "Gives the money of an order back"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Devuelto")

        data class Args(val order: Int)
    }

    class NamedLikeSearch: Tool<JsonObject>() {
        override val name = "search_tools"
        override val description = "Searches the tools of the application"

        override fun execute(args: JsonObject, context: ToolContext) = ToolResult.text("nada")
    }
}
