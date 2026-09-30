package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tool loop with tools to search for against what OpenAI really answered: gpt-5.4 searching with its hosted
 * tool search, loading getWeather among the deferred tools and calling it in the same answer. OpenAI took the second
 * call of each recording, with the search sent back as it came and the call with the namespace it gave it, so what
 * these tests pin down is what it took.
 */
class OpenAIToolSearchTest {
    @Test
    fun `the provider searches, and the tool it found runs`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        val result = loop().run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.text).isEqualTo("En Bariloche hay 7 °C ahora.")
    }

    @Test
    fun `the search is not run by the loop, and the next call sends it back as it came`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))
        val recorded = Json.parse(fixture("tool-search-1.json")).asObject()!!["output"]!!.asArray()!!

        val result = loop().run(request())

        assertThat(result.steps[0].toolResults.map { it.toolName }).containsExactly("getWeather")
        val input = sent(1)["input"]!!.asArray()!!
        assertThat(input[1]).isEqualTo(recorded[0])
        assertThat(input[2]).isEqualTo(recorded[1])
    }

    @Test
    fun `and the call to the tool it found goes back with its namespace`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        loop().run(request())

        val call = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }.single { it.type == "function_call" }
        assertThat(call["namespace"]?.asString()).isEqualTo("getWeather")
    }

    @Test
    fun `every call tells the deferred tools, and the tool search after them`() {
        http.answers(fixture("tool-search-1.json"), fixture("tool-search-2.json"))

        loop().run(request())

        (0..1).forEach { call ->
            val tools = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(tools.map { it["name"]?.asString() ?: it.type })
                .containsExactly("getTime", "getWeather", "refund", "listInvoices", "tool_search")
            assertThat(tools.filter { it["defer_loading"]?.asBoolean() == true }.map { it["name"]?.asString() })
                .containsExactly("getWeather", "refund", "listInvoices")
        }
    }

    @Test
    fun `and so does a stream`() {
        http.answers(fixture("tool-search-stream-1.txt"), fixture("tool-search-stream-2.txt"))

        val result = loop().stream(request()).use { it.forEach { }; it.result() }

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        val input = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(input.drop(1).map { it.type })
            .containsExactly("tool_search_call", "tool_search_output", "function_call", "function_call_output")
        assertThat(input.single { it.type == "function_call" }["namespace"]?.asString()).isEqualTo("getWeather")
        assertThat(result.text).isNotBlank()
    }

    private fun loop() =
        ToolLoop(model, listOf(TimeTool()), searchableTools = listOf(weather, RefundTool(), InvoicesTool()))

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
