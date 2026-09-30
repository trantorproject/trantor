package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.ai.tools.search.ProviderToolSearcher
import dev.botta.trantor.ai.tools.search.ToolSearcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tool loop with tools to search for against what OpenAI really answered, gpt-5.4 loading getWeather among the
 * deferred tools in both ways: with the search of Trantor, which is how a run searches unless told otherwise, run by
 * the client and answered with the definitions of the tools found; and with its own hosted tool search, asked for with
 * [ProviderToolSearcher], which searches and calls in the same answer. OpenAI took the calls after the first of each
 * recording, the search sent back as it came and the call with the namespace it gave it, so what these tests pin down
 * is what it took.
 */
class OpenAIToolSearchTest {
    @Test
    fun `asked for its own search, the provider searches, and the tool it found runs`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        val result = loop(ProviderToolSearcher).run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.text).isEqualTo("En Bariloche hay 7 °C ahora.")
    }

    @Test
    fun `the search is not run by the loop, and the next call sends it back as it came`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))
        val recorded = Json.parse(fixture("tool-search-1.json")).asObject()!!["output"]!!.asArray()!!

        val result = loop(ProviderToolSearcher).run(request())

        assertThat(result.steps[0].toolResults.map { it.toolName }).containsExactly("getWeather")
        val input = sent(1)["input"]!!.asArray()!!
        assertThat(input[1]).isEqualTo(recorded[0])
        assertThat(input[2]).isEqualTo(recorded[1])
    }

    @Test
    fun `and the call to the tool it found goes back with its namespace`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        loop(ProviderToolSearcher).run(request())

        val call = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }.single { it.type == "function_call" }
        assertThat(call["namespace"]?.asString()).isEqualTo("getWeather")
    }

    @Test
    fun `every call tells the deferred tools, and the tool search after them`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        loop(ProviderToolSearcher).run(request())

        (0..1).forEach { call ->
            val tools = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(tools.map { it["name"]?.asString() ?: it.type })
                .containsExactly("getTime", "getWeather", "refund", "listInvoices", "tool_search")
            assertThat(tools.filter { it["defer_loading"]?.asBoolean() == true }.map { it["name"]?.asString() })
                .containsExactly("getWeather", "refund", "listInvoices")
        }
    }

    @Test
    fun `and so does a stream searched by the provider`() {
        http.answers(fixture("tool-search-stream-1.txt"), fixture("tool-search-stream-2.txt"))

        val result = loop(ProviderToolSearcher).stream(request()).use { it.forEach { }; it.result() }

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        val input = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(input.drop(1).map { it.type })
            .containsExactly("tool_search_call", "tool_search_output", "function_call", "function_call_output")
        assertThat(input.single { it.type == "function_call" }["namespace"]?.asString()).isEqualTo("getWeather")
        assertThat(result.text).isNotBlank()
    }

    @Test
    fun `by default Trantor searches, and what it found goes back as the definitions of the tools, and one runs`() {
        http.answers(*(1..3).map { fixture("tool-search-client-$it.json") }.toTypedArray())

        val result = loop().run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.text).isEqualTo(recordedText("tool-search-client-3.json"))
        val input = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(input.drop(1).map { it.type }).containsExactly("tool_search_call", "tool_search_output")
        assertThat(input[2]["call_id"]).isEqualTo(input[1]["call_id"])
        assertThat(input[2]["tools"]!!.asArray()!!.map { it.asObject()!!["name"]?.asString() })
            .containsExactly("getWeather")
    }

    @Test
    fun `and every call asks the client for the search of Trantor, with no hosted one`() {
        http.answers(*(1..3).map { fixture("tool-search-client-$it.json") }.toTypedArray())

        loop().run(request())

        (0..2).forEach { call ->
            val tools = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(tools.map { it["name"]?.asString() ?: "${it.type}:${it["execution"]?.asString()}" })
                .containsExactly("tool_search:client", "getTime", "getWeather", "refund", "listInvoices")
        }
    }

    @Test
    fun `and so does a stream searched by Trantor`() {
        http.answers(*(1..3).map { fixture("tool-search-client-stream-$it.txt") }.toTypedArray())

        val result = loop().stream(request()).use { it.forEach { }; it.result() }

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        val input = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(input.drop(1).map { it.type }).containsExactly("tool_search_call", "tool_search_output")
        assertThat(result.text).isNotBlank()
    }

    private fun loop(searcher: ToolSearcher? = null) = ToolLoop(
        model,
        listOf(TimeTool()),
        searchableTools = listOf(weather, RefundTool(), InvoicesTool()),
        toolSearcher = searcher,
    )

    private fun recordedText(name: String) = Json.parse(fixture(name)).asObject()!!["output"]!!.asArray()!!
        .first { it.asObject()!!.type == "message" }.asObject()!!["content"]!!.asArray()!![0].asObject()!!["text"]!!
        .asString()

    private fun request() =
        ChatRequest(listOf(Message.user("Que temperatura hay en Bariloche? Busca la tool que lo sepa.")))

    private fun sent(call: Int) = Json.parse(http.requests[call].body as String).asObject()!!

    private val JsonObject.type get() = this["type"]?.asString()

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = OpenAIChatModel("gpt-5.4", OpenAIConfig(apiKey = "sk-test"), http)
    private val weather = WeatherTool()

    /** The tools the recording was made with, answering what they answered then. */
    class TimeTool: Tool<TimeTool.Args>() {
        override val name = "getTime"
        override val description = "The current time"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("12:00")

        class Args
    }

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        val cities = mutableListOf<String>()

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities.add(args.city)
            return ToolResult.json(Json.obj("celsius" to 7))
        }

        data class Args(val city: String)
    }

    class RefundTool: Tool<RefundTool.Args>() {
        override val name = "refund"
        override val description = "Gives the money of an order back"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Refunded")

        data class Args(val order: Int)
    }

    class InvoicesTool: Tool<InvoicesTool.Args>() {
        override val name = "listInvoices"
        override val description = "The invoices of a customer"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("None")

        data class Args(val customer: String)
    }
}
