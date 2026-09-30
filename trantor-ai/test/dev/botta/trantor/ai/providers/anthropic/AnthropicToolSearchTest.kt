package dev.botta.trantor.ai.providers.anthropic

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
 * The tool loop with tools to search for against what Anthropic really answered, Claude Sonnet 4.5 finding getWeather
 * among the deferred tools in both ways: with the search of Trantor, which is how a run searches unless told
 * otherwise, whose answer Anthropic loads the tools from; and with its own BM25 tool search, asked for with
 * [ProviderToolSearcher], which searches and calls in the same answer. Anthropic took the calls after the first of
 * each recording, so what these tests pin down is what it took.
 */
class AnthropicToolSearchTest {
    @Test
    fun `asked for its own search, the provider searches, and the tool it found runs`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        val result = loop(ProviderToolSearcher).run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.text).isEqualTo("La temperatura actual en Bariloche es de **7°C** (grados Celsius).")
    }

    @Test
    fun `the search is not run by the loop, and the next call sends it back as it came`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))
        val recorded = Json.parse(fixture("tool-search-1.json")).asObject()!!["content"]!!.asArray()!!

        val result = loop(ProviderToolSearcher).run(request())

        assertThat(result.steps[0].toolResults.map { it.toolName }).containsExactly("getWeather")
        val answer = assistantTurn()
        assertThat(answer[1]).isEqualTo(recorded[1])
        assertThat(answer[2]).isEqualTo(recorded[2])
    }

    @Test
    fun `every call tells the deferred tools, since the provider expands what it found from them`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        loop(ProviderToolSearcher).run(request())

        (0..1).forEach { call ->
            val tools = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(tools.map { it.name })
                .containsExactly("getTime", "getWeather", "refund", "listInvoices", "tool_search_tool_bm25")
            assertThat(tools.filter { it["defer_loading"]?.asBoolean() == true }.map { it.name })
                .containsExactly("getWeather", "refund", "listInvoices")
        }
    }

    @Test
    fun `and so does a stream searched by the provider`() {
        http.answers(fixture("tool-search-stream-1.txt"), fixture("tool-search-stream-2.txt"))

        val result = loop(ProviderToolSearcher).stream(request()).use { it.forEach { }; it.result() }

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(assistantTurn().map { it.asObject()!!.type })
            .containsExactly("text", "server_tool_use", "tool_search_tool_result", "text", "tool_use")
        assertThat(result.text).isNotBlank()
    }

    @Test
    fun `by default Trantor searches, and what it found goes back as references to the tools, and one runs`() {
        http.answers(*(1..3).map { fixture("tool-search-client-$it.json") }.toTypedArray())

        val result = loop().run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.text).isEqualTo(recordedText("tool-search-client-3.json"))
        val reply = sent(1)["messages"]!!.asArray()!![2].asObject()!!["content"]!!.asArray()!![0].asObject()!!
        assertThat(reply["content"].toString()).isEqualTo("""[{"type":"tool_reference","tool_name":"getWeather"}]""")
    }

    @Test
    fun `and every call tells the deferred tools, and the search of Trantor up front, not Anthropic's`() {
        http.answers(*(1..3).map { fixture("tool-search-client-$it.json") }.toTypedArray())

        loop().run(request())

        (0..2).forEach { call ->
            val tools = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(tools.map { it.name })
                .containsExactly("getTime", "search_tools", "getWeather", "refund", "listInvoices")
            assertThat(tools.filter { it["defer_loading"]?.asBoolean() == true }.map { it.name })
                .containsExactly("getWeather", "refund", "listInvoices")
        }
    }

    @Test
    fun `and so does a stream searched by Trantor`() {
        http.answers(*(1..3).map { fixture("tool-search-client-stream-$it.txt") }.toTypedArray())

        val result = loop().stream(request()).use { it.forEach { }; it.result() }

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        val reply = sent(1)["messages"]!!.asArray()!![2].asObject()!!["content"]!!.asArray()!![0].asObject()!!
        assertThat(reply["content"].toString()).isEqualTo("""[{"type":"tool_reference","tool_name":"getWeather"}]""")
        assertThat(result.text).isNotBlank()
    }

    private fun loop(searcher: ToolSearcher? = null) = ToolLoop(
        model,
        listOf(TimeTool()),
        searchableTools = listOf(weather, RefundTool(), InvoicesTool()),
        toolSearcher = searcher,
    )

    private fun recordedText(name: String) =
        Json.parse(fixture(name)).asObject()!!["content"]!!.asArray()!![0].asObject()!!["text"]!!.asString()

    private fun request() =
        ChatRequest(listOf(Message.user("Que temperatura hay en Bariloche? Busca la tool que lo sepa.")))

    private fun sent(call: Int) = Json.parse(http.requests[call].body as String).asObject()!!

    /** The content of the answer of the first call, as the second call sends it back. */
    private fun assistantTurn() = sent(1)["messages"]!!.asArray()!![1].asObject()!!["content"]!!.asArray()!!

    private val JsonObject.type get() = this["type"]?.asString()

    private val JsonObject.name get() = this["name"]?.asString()

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)
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
