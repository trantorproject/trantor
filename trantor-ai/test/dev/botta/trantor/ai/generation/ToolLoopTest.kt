@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.Cancellation
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolLoopTest {
    @Test
    fun `an answer without tool calls is a single step`() {
        model.answers(listOf(TextPart("Hola")))

        val steps = loop().run(ChatRequest("Hola")).steps

        assertThat(steps).hasSize(1)
        assertThat(steps.single().response.text).isEqualTo("Hola")
        assertThat(steps.single().toolResults).isEmpty()
    }

    @Test
    fun `runs the tool the model asked for and calls the model again with its result`() {
        model.answers(listOf(weatherCall("call_1", "Bariloche")), listOf(TextPart("Hacen 7 grados")))

        val steps = loop().run(ChatRequest("Que temperatura hay en Bariloche?")).steps

        assertThat(weather.cities).containsExactly("Bariloche")
        assertThat(steps).hasSize(2)
        assertThat(steps[0].toolResults.single())
            .isEqualTo(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))
        assertThat(steps[1].response.text).isEqualTo("Hacen 7 grados")
    }

    @Test
    fun `the model is told about the tools, next to the ones the request already had`() {
        val webSearch = ProviderToolSpec("openai.web_search", Json.obj())

        loop().run(ChatRequest(listOf(Message.user("Hola")), tools = listOf(webSearch)))

        assertThat(model.request?.tools).containsExactly(webSearch, weather.spec())
    }

    @Test
    fun `the next call carries the answer of the model whole and then the results`() {
        // Reasoning and parts we do not model have to go back as they came, or the provider loses the thread
        val reasoning = ReasoningPart(text = "Busco el clima", opaque = Json.obj("signature" to "abc"))
        val call = weatherCall("call_1", "Bariloche")
        model.answers(listOf(reasoning, call), listOf(TextPart("Hacen 7 grados")))
        val question = Message.user("Que temperatura hay en Bariloche?")

        loop().run(ChatRequest(question))

        assertThat(model.requests[1].messages).containsExactly(
            question,
            Message.Assistant(listOf(reasoning, call)),
            Message.Tool(listOf(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))),
        )
    }

    @Test
    fun `the dynamic system prompt goes on every step and never into the conversation`() {
        model.answers(listOf(weatherCall("call_1", "Bariloche")), listOf(TextPart("Hacen 7 grados")))
        val request = ChatRequest(listOf(Message.user("Que temperatura hay?")), dynamicSystem = "Hoy es martes")

        val result = loop().run(request)

        assertThat(model.requests.map { it.dynamicSystem }).containsExactly("Hoy es martes", "Hoy es martes")
        assertThat(model.requests[1].messages.filterIsInstance<Message.System>()).isEmpty()
        assertThat(result.newMessages.filterIsInstance<Message.System>()).isEmpty()
    }

    @Test
    fun `the calls of one turn run in order and their results go back together`() {
        model.answers(
            listOf(weatherCall("call_1", "Bariloche"), weatherCall("call_2", "Ushuaia")),
            listOf(TextPart("Frio en los dos")),
        )

        val steps = loop().run(ChatRequest("Y en Bariloche y Ushuaia?")).steps

        assertThat(weather.cities).containsExactly("Bariloche", "Ushuaia")
        assertThat(model.requests[1].messages.last())
            .isEqualTo(Message.Tool(steps[0].toolResults))
        assertThat(steps[0].toolResults.map { it.callId }).containsExactly("call_1", "call_2")
    }

    @Test
    fun `the new messages are what the run added to the conversation, for the next request`() {
        val call = weatherCall("call_1", "Bariloche")
        model.answers(listOf(call), listOf(TextPart("Hacen 7 grados")))

        val result = loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

        assertThat(result.newMessages).containsExactly(
            Message.Assistant(listOf(call)),
            Message.Tool(listOf(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))),
            Message.Assistant(listOf(TextPart("Hacen 7 grados"))),
        )
    }

    @Test
    fun `a tool the provider ran is not run again`() {
        val search = ToolCallPart("srv_1", "web_search", Json.obj("query" to "clima"), providerExecuted = true)
        model.answers(listOf(search, TextPart("Hacen 7 grados")))

        val steps = loop().run(ChatRequest("Que temperatura hay?")).steps

        assertThat(steps).hasSize(1)
        assertThat(model.requests).hasSize(1)
    }

    @Test
    fun `the tool gets the id of its call and the context of the run`() {
        model.answers(listOf(weatherCall("call_1", "Bariloche")))
        val run = RunContext("acme")

        loop(run = run).run(ChatRequest("Que temperatura hay en Bariloche?"))

        assertThat(weather.context?.callId).isEqualTo("call_1")
        assertThat(weather.context?.toolName).isEqualTo("getWeather")
        assertThat(weather.context?.run).isSameAs(run)
    }

    @Test
    fun `every call gets the options of the generation`() {
        model.answers(listOf(weatherCall("call_1", "Bariloche")))
        val options = CallOptions(headers = mapOf("x-tenant" to "acme"))

        loop().run(ChatRequest("Que temperatura hay en Bariloche?"), options)

        assertThat(model.options).isSameAs(options)
    }

    @Nested
    inner class `when to stop` {
        @Test
        fun `past the steps allowed it fails with the ones it did`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche")), listOf(weatherCall("call_2", "Ushuaia")))

            assertThatThrownBy { loop(maxSteps = 2).run(ChatRequest("Que temperatura hay?")) }
                .isInstanceOfSatisfying(MaxStepsExceededError::class.java) {
                    assertThat(it.maxSteps).isEqualTo(2)
                    assertThat(it.result.steps).hasSize(2)
                }
        }

        @Test
        fun `without running the calls of the last step, since no model would read their results`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche")), listOf(weatherCall("call_2", "Ushuaia")))

            runCatching { loop(maxSteps = 2).run(ChatRequest("Que temperatura hay?")) }

            assertThat(weather.cities).containsExactly("Bariloche")
        }

        @Test
        fun `and leaves out of the new messages the call nobody answered, which a provider would reject`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche")), listOf(weatherCall("call_2", "Ushuaia")))

            val error = runCatching { loop(maxSteps = 2).run(ChatRequest("Que temperatura hay?")) }

            val messages = (error.exceptionOrNull() as MaxStepsExceededError).result.newMessages
            assertThat(messages).hasSize(2)
            assertThat(messages.last()).isInstanceOf(Message.Tool::class.java)
        }

        @Test
        fun `ten steps unless told otherwise`() {
            repeat(10) { model.answers(listOf(weatherCall("call_$it", "Bariloche"))) }

            assertThatThrownBy { ToolLoop(model, listOf(weather)).run(ChatRequest("Hola")) }
                .isInstanceOfSatisfying(MaxStepsExceededError::class.java) {
                    assertThat(it.result.steps).hasSize(10)
                }
        }

        @Test
        fun `a cancellation while a tool runs stops before calling the model again`() {
            val cancellation = Cancellation()
            weather.onExecute = { cancellation.cancel() }
            model.answers(listOf(weatherCall("call_1", "Bariloche")))

            assertThatThrownBy { loop().run(ChatRequest("Hola"), CallOptions(cancellation = cancellation)) }
                .isInstanceOf(CancelledError::class.java)
            assertThat(model.requests).hasSize(1)
        }

        @Test
        fun `and so does an interrupted thread`() {
            weather.onExecute = { Thread.currentThread().interrupt() }
            model.answers(listOf(weatherCall("call_1", "Bariloche")))

            try {
                assertThatThrownBy { loop().run(ChatRequest("Hola")) }.isInstanceOf(CancelledError::class.java)
                assertThat(model.requests).hasSize(1)
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun loop(maxSteps: Int = 5, run: RunContext = RunContext()) =
        ToolLoop(model, listOf(weather), maxSteps, run)

    private fun weatherCall(callId: String, city: String) =
        ToolCallPart(callId, "getWeather", Json.obj("city" to city))

    private val model = FakeChatModel()
    private val weather = WeatherTool()

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        val cities = mutableListOf<String>()
        var context: ToolContext? = null
        var onExecute: () -> Unit = {}

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities.add(args.city)
            this.context = context
            onExecute()
            return ToolResult.text("7 grados en ${args.city}")
        }

        @Serializable
        data class Args(val city: String)
    }
}
