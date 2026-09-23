@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
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

    @Test
    fun `the tools get the context the step makes for them`() {
        first.answers(listOf(call("call_1", "getWeather")))
        val contexts = mutableListOf<ToolContext>()
        weather.onExecute = { contexts.add(it) }
        val made = ToolContext("call_1", "getWeather")

        ToolLoop({ request, _ -> StepSetup(first, request, listOf(weather), toolContext = { made }) })
            .run(ChatRequest("Que temperatura hay?"))

        assertThat(contexts).containsExactly(made)
    }

    /** A step whose answer is a call: the run ends when the model calls it and not when it stops calling tools. */
    @Nested
    inner class `With an output tool` {
        @Test
        fun `a call to it ends the run once every call of the step ran`() {
            first.answers(listOf(call("call_1", "getWeather"), city("call_2", "Bariloche")))

            val result = loop().run(ChatRequest("Que temperatura hay?"))

            assertThat(first.requests).hasSize(1)
            assertThat(weather.calls).isEqualTo(1)
            assertThat(result.steps.single().toolResults.map { it.toolName }).containsExactly("getWeather", "final")
            assertThat(result.newMessages).hasSize(2)
        }

        @Test
        fun `a call to it the model got wrong goes back to it, and the run goes on`() {
            first.answers(
                listOf(ToolCallPart("call_1", "final", Json.obj("town" to "Bariloche"))),
                listOf(city("call_2", "Bariloche")),
            )

            val result = loop().run(ChatRequest("Que temperatura hay?"))

            assertThat(result.steps).hasSize(2)
            assertThat(result.steps[0].toolResults.single().isError).isTrue()
            assertThat(result.steps[1].toolResults.single().isError).isFalse()
        }

        @Test
        fun `on the last step it still ends the run, since no other call to the model is needed`() {
            first.answers(listOf(call("call_1", "getWeather")), listOf(city("call_2", "Bariloche")))

            val result = loop(maxSteps = 2).run(ChatRequest("Que temperatura hay?"))

            assertThat(result.steps).hasSize(2)
        }

        @Test
        fun `an answer without it reminds the model, and the reminder stays in the conversation`() {
            first.answers(listOf(TextPart("Bariloche")), listOf(city("call_1", "Bariloche")))

            val result = loop().run(ChatRequest("Que ciudad?"))
            val reminder = Message.user("Please include your response in a call to final.")

            assertThat(first.requests[1].messages.last()).isEqualTo(reminder)
            assertThat(result.steps[0].reminder).isEqualTo(reminder)
            assertThat(result.newMessages[1]).isEqualTo(reminder)
            assertThat(result.steps).hasSize(2)
        }

        @Test
        fun `a second answer without it in a row ends the run with that answer`() {
            first.answers(listOf(TextPart("Bariloche")), listOf(TextPart("Te dije Bariloche")))

            val result = loop().run(ChatRequest("Que ciudad?"))

            assertThat(result.steps).hasSize(2)
            assertThat(result.text).isEqualTo("Te dije Bariloche")
        }

        @Test
        fun `the stream ends the same ways`() {
            first.answers(listOf(TextPart("Bariloche")), listOf(city("call_1", "Bariloche")))

            val result = loop().stream(ChatRequest("Que ciudad?")).use { it.result() }

            assertThat(result.steps).hasSize(2)
            assertThat(result.steps[0].reminder).isNotNull()
            assertThat(result.steps[1].toolResults.single().toolName).isEqualTo("final")
        }

        private fun loop(maxSteps: Int = 5) = ToolLoop(
            { request, _ -> StepSetup(first, request, listOf(weather, output), outputTool = "final") },
            maxSteps,
        )

        private fun city(callId: String, city: String) = ToolCallPart(callId, "final", Json.obj("city" to city))

        private val output = OutputTool()
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
        var onExecute: (ToolContext) -> Unit = {}

        override fun execute(args: City, context: ToolContext): ToolResult {
            calls++
            onExecute(context)
            return ToolResult.text("7 grados")
        }
    }

    class OutputTool: Tool<City>(City.serializer()) {
        override val name = "final"
        override val description = "The city asked for"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("Final result processed.")
    }

    class PriceTool: Tool<City>(City.serializer()) {
        override val name = "getPrice"
        override val description = "The price of a trip to a city"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("100 dólares")
    }

    @Serializable
    data class City(val city: String)
}
