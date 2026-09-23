package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A loop whose steps do not all go out the same, which is what an agent needs: it is asked before each one. */
class ToolLoopStepsTest {
    @Test
    fun `each step is set up with the conversation so far and the steps done`() {
        first.answers(listOf(call("call_1", "getWeather")), listOf(TextPart("7 grados")))
        val seen = mutableListOf<Pair<ChatRequest, List<Step>>>()
        val question = Message.user("Que temperatura hay?")

        ToolLoop({ request, steps -> seen.add(request to steps); StepSetup(first, request, listOf(weather)) })
            .run(ChatRequest(question))

        assertThat(seen.map { it.first.messages.size }).containsExactly(1, 3)
        assertThat(seen[1].first.messages.first()).isEqualTo(question)
        assertThat(seen.map { it.second.size }).containsExactly(0, 1)
    }

    @Test
    fun `a step can go out with another model, other instructions and other tools`() {
        first.answers(listOf(call("call_1", "getWeather")))
        second.answers(listOf(TextPart("7 grados")))

        val result = ToolLoop({ request, steps ->
            if (steps.isEmpty()) {
                StepSetup(first, request.copy(dynamicSystem = "Sos soporte"), listOf(weather))
            } else {
                StepSetup(second, request.copy(dynamicSystem = "Sos ventas"), listOf(price))
            }
        }).run(ChatRequest("Que temperatura hay?"))

        assertThat(first.requests.single().dynamicSystem).isEqualTo("Sos soporte")
        assertThat(first.requests.single().toolNames()).containsExactly("getWeather")
        assertThat(second.requests.single().dynamicSystem).isEqualTo("Sos ventas")
        assertThat(second.requests.single().toolNames()).containsExactly("getPrice")
        assertThat(result.text).isEqualTo("7 grados")
    }

    @Test
    fun `what a step sends is not what the conversation keeps`() {
        // A step may leave messages out of what the model gets; the next one still has them all
        first.answers(listOf(call("call_1", "getWeather")), listOf(TextPart("7 grados")))
        val seen = mutableListOf<Int>()

        ToolLoop({ request, _ ->
            seen.add(request.messages.size)
            StepSetup(first, request.copy(messages = request.messages.takeLast(1)), listOf(weather))
        }).run(ChatRequest(Message.user("Hola"), Message.user("Que temperatura hay?")))

        assertThat(seen).containsExactly(2, 4)
        assertThat(first.requests.map { it.messages.size }).containsExactly(1, 1)
    }

    @Test
    fun `the calls of a step run with the tools of the step that asked for them`() {
        first.answers(listOf(call("call_1", "getWeather")), listOf(call("call_2", "getWeather")))

        val result = ToolLoop({ request, steps ->
            StepSetup(first, request, if (steps.isEmpty()) listOf(weather) else listOf(price))
        }).run(ChatRequest("Que temperatura hay?"))

        assertThat(weather.calls).isEqualTo(1)
        assertThat(result.steps[1].toolResults.single().isError).isTrue()
        assertThat((result.steps[1].toolResults.single().output as ToolOutput.Text).value)
            .isEqualTo("There is no tool called getWeather. The tools are: getPrice")
    }

    @Test
    fun `a tool is described again on every step`() {
        first.answers(listOf(call("call_1", "getWeather")), listOf(TextPart("7 grados")))

        ToolLoop(first, listOf(weather)).run(ChatRequest("Que temperatura hay?"))

        assertThat(first.requests.map { (it.tools.single() as FunctionToolSpec).description })
            .containsExactly("The weather, asked 0 times", "The weather, asked 1 times")
    }

    @Test
    fun `the stream sets up each step the same way`() {
        first.answers(listOf(call("call_1", "getWeather")))
        second.answers(listOf(TextPart("7 grados")))

        val result = ToolLoop({ request, steps ->
            StepSetup(if (steps.isEmpty()) first else second, request, listOf(weather))
        }).stream(ChatRequest("Que temperatura hay?")).use { it.result() }

        assertThat(first.requests).hasSize(1)
        assertThat(second.requests).hasSize(1)
        assertThat(result.text).isEqualTo("7 grados")
    }

    private fun ChatRequest.toolNames() = tools.map { (it as FunctionToolSpec).name }

    private fun call(callId: String, tool: String) = ToolCallPart(callId, tool, Json.obj("city" to "Bariloche"))

    private val first = FakeChatModel(modelId = "first")
    private val second = FakeChatModel(modelId = "second")
    private val weather = WeatherTool()
    private val price = PriceTool()

    /** Its description counts its calls, as one that says what the application has at that moment would change. */
    class WeatherTool: Tool<City>(City.serializer()) {
        override val name = "getWeather"
        override val description get() = "The weather, asked $calls times"

        var calls = 0

        override fun execute(args: City, context: ToolContext): ToolResult {
            calls++
            return ToolResult.text("7 grados")
        }
    }

    class PriceTool: Tool<City>(City.serializer()) {
        override val name = "getPrice"
        override val description = "The price of a trip to a city"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("100 dólares")
    }

    @Serializable
    data class City(val city: String)
}
