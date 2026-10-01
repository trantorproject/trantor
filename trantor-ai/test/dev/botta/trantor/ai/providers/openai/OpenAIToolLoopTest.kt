package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tool loop against what OpenAI really answered: o4-mini reasoning at low effort, asking for the weather and
 * then answering with it. The api accepted the second call of the recording, so what these tests pin down is what
 * it took: the reasoning item of the first answer going back with its encrypted content.
 */
class OpenAIToolLoopTest {
    @Test
    fun `runs the recorded loop to the answer`() {
        http.answers(fixture("tool-loop/loop-1"), fixture("tool-loop/loop-2"))

        val result = loop().run(request())

        assertThat(weather.cities).containsExactly("Bariloche, Argentina")
        assertThat(result.steps).hasSize(2)
        assertThat(result.text).isEqualTo("En Bariloche la temperatura actual es de 7 °C.")
        assertThat(result.usage.inputTokens).isEqualTo(58 + 172)
        assertThat(result.usage.outputTokens).isEqualTo(100 + 18)
    }

    @Test
    fun `the second call sends the reasoning back as it came, then the call and its result`() {
        http.answers(fixture("tool-loop/loop-1"), fixture("tool-loop/loop-2"))
        val recorded = Json.parse(fixture("tool-loop/loop-1")).asObject()!!["output"]!!.asArray()!!
        val reasoning = recorded[0].asObject()!!

        loop().run(request())

        val input = sent(1)["input"]!!.asArray()!!.map { it.asObject()!! }
        val sentReasoning = input.single { it.type == "reasoning" }
        val call = input.single { it.type == "function_call" }
        val output = input.single { it.type == "function_call_output" }

        assertThat(sentReasoning["id"]).isEqualTo(reasoning["id"])
        assertThat(sentReasoning["encrypted_content"]).isEqualTo(reasoning["encrypted_content"])
        assertThat(call["call_id"]?.asString()).isEqualTo("call_YAzyZWGQCR8mFAYDVqxoQy2E")
        assertThat(output["call_id"]?.asString()).isEqualTo("call_YAzyZWGQCR8mFAYDVqxoQy2E")
        assertThat(output["output"]?.asString()).isEqualTo("""{"celsius":7}""")
        assertThat(input.indexOf(sentReasoning)).isLessThan(input.indexOf(call))
        assertThat(input.indexOf(call)).isLessThan(input.indexOf(output))
    }

    @Test
    fun `with an object asked for, the model calls the tool first and answers with the object`() {
        http.answers(fixture("tool-loop/object-1"), fixture("tool-loop/object-2"))

        val result = loop().run(request(OutputSpec.json<CityWeather>(GsonSerializer())))

        assertThat(weather.cities).containsExactly("San Carlos de Bariloche")
        assertThat(result.response.objectAs<CityWeather>(GsonSerializer())).isEqualTo(CityWeather("Bariloche", 7))
        assertThat(http.requests.map { body(it.body)["text"]?.asObject()?.get("format")?.asObject()?.type })
            .containsExactly("json_schema", "json_schema")
    }

    private fun loop() = ToolLoop(model, listOf(weather))

    private fun request(output: OutputSpec = OutputSpec.Text) = ChatRequest(
        messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
        output = output,
        settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto)),
    )

    private fun sent(call: Int) = body(http.requests[call].body)

    private fun body(body: Any?) = Json.parse(body as String).asObject()!!

    private val JsonObject.type get() = this["type"]?.asString()

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = OpenAIChatModel("o4-mini", OpenAIConfig(apiKey = "sk-test"), http)
    private val weather = WeatherTool()

    data class CityWeather(val city: String, val celsius: Int)

    /** The tool the recording was made with, answering what it answered then. */
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
}
