package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tool loop against what Anthropic really answered: Claude Sonnet 4.5 thinking, asking for the weather and
 * then answering with it. Anthropic refuses a thinking block that does not come back as it was signed, and it
 * accepted the second call of the recording, so what these tests pin down is what it took.
 */
class AnthropicToolLoopTest {
    @Test
    fun `runs the recorded loop to the answer`() {
        http.answers(fixture("tool-loop-1"), fixture("tool-loop-2"))

        val result = loop().run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.steps).hasSize(2)
        assertThat(result.text).isEqualTo("La temperatura actual en Bariloche, Argentina es de **7°C**.")
        assertThat(result.usage.inputTokens).isEqualTo(616 + 768)
        assertThat(result.usage.outputTokens).isEqualTo(136 + 75)
    }

    @Test
    fun `the second call sends the signed thinking back as it came, then the call and its result`() {
        http.answers(fixture("tool-loop-1"), fixture("tool-loop-2"))
        val recorded = Json.parse(fixture("tool-loop-1")).asObject()!!["content"]!!.asArray()!!
        val thinking = recorded[0].asObject()!!

        loop().run(request())

        val messages = sent(1)["messages"]!!.asArray()!!.map { it.asObject()!! }
        val answer = messages[1].blocks()
        val results = messages[2].blocks()

        assertThat(messages.map { it["role"]?.asString() }).containsExactly("user", "assistant", "user")
        assertThat(answer.map { it.type }).containsExactly("thinking", "tool_use")
        assertThat(answer[0]["thinking"]).isEqualTo(thinking["thinking"])
        assertThat(answer[0]["signature"]).isEqualTo(thinking["signature"])
        assertThat(answer[1]["id"]?.asString()).isEqualTo("toolu_01XCyTE71xVmXCy731sYwL9K")
        assertThat(results.map { it.type }).containsExactly("tool_result")
        assertThat(results[0]["tool_use_id"]?.asString()).isEqualTo("toolu_01XCyTE71xVmXCy731sYwL9K")
    }

    @Test
    fun `with an object asked for, the model calls the tool first and answers with the object`() {
        http.answers(fixture("tool-loop-object-1"), fixture("tool-loop-object-2"))

        val result = loop().run(request(OutputSpec.json<CityWeather>()))

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.response.objectAs<CityWeather>()).isEqualTo(CityWeather("Bariloche, Argentina", 7))
        assertThat(http.requests.map { body(it.body).outputFormat() }).containsExactly("json_schema", "json_schema")
    }

    private fun loop() = ToolLoop(model, listOf(weather))

    private fun request(output: OutputSpec = OutputSpec.Text) = ChatRequest(
        messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
        output = output,
        settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto)),
    )

    private fun sent(call: Int) = body(http.requests[call].body)

    private fun body(body: Any?) = Json.parse(body as String).asObject()!!

    private fun JsonObject.blocks() = this["content"]!!.asArray()!!.map { it.asObject()!! }

    private fun JsonObject.outputFormat() =
        this["output_config"]?.asObject()?.get("format")?.asObject()?.type

    private val JsonObject.type get() = this["type"]?.asString()

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)
    private val weather = WeatherTool()

    @Serializable
    data class CityWeather(val city: String, val celsius: Int)

    /** The tool the recording was made with, answering what it answered then. */
    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        val cities = mutableListOf<String>()

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities.add(args.city)
            return ToolResult.json(Json.obj("celsius" to 7))
        }

        @Serializable
        data class Args(val city: String)
    }
}
