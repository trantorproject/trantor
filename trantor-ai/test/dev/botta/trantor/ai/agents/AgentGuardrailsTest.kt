@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Checks that stop a run: on what the user asked, on the answer and on the calls to the tools. */
class AgentGuardrailsTest {
    @Nested
    inner class `On the input` {
        @Test
        fun `one that trips stops the run before calling the model, saying which one it was and why`() {
            val offTopic = InputGuardrail("off-topic") { _, _ ->
                GuardrailVerdict.Trip("It is not about the weather", 0.9)
            }

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                runner.run(support.inputGuardrails(offTopic).build(), question)
            }

            assertThat(error.guardrail).isEqualTo("off-topic")
            assertThat(error.kind).isEqualTo(GuardrailKinds.Input)
            assertThat(error.reason).isEqualTo("It is not about the weather")
            assertThat(error.details).isEqualTo(0.9)
            assertThat(error.agent.name).isEqualTo("support")
            assertThat(error.result).isNull()
            assertThat(error)
                .hasMessage("The input guardrail off-topic stopped the run of support: It is not about the weather")
            assertThat(supportModel.requests).isEmpty()
        }

        @Test
        fun `it checks the conversation the run got, as the agent it starts with`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val seen = mutableListOf<String>()
            val watching = InputGuardrail("watching") { run, conversation ->
                seen.add("${run.agent.name}: ${conversation.size}")
                GuardrailVerdict.Pass
            }

            val result = runner.run(support.inputGuardrails(watching).build(), Message.user("Hola"), question)

            assertThat(seen).containsExactly("support: 2")
            assertThat(result.text).isEqualTo("7 grados")
        }
    }

    @Nested
    inner class `On the output` {
        @Test
        fun `one that trips stops the run on the final answer, with everything the run left`() {
            supportModel.answers(listOf(weatherCall), listOf(TextPart("Hacen 7 grados, idiota")))
            val log = AgentHooksTest.Log("hook")
            val polite = OutputGuardrail("polite") { _, result ->
                if ("idiota" in result.text) GuardrailVerdict.Trip("Rude") else GuardrailVerdict.Pass
            }

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                runner.run(support.outputGuardrails(polite).hooks(log).build(), question)
            }

            assertThat(error.kind).isEqualTo(GuardrailKinds.Output)
            assertThat(error.result!!.text).isEqualTo("Hacen 7 grados, idiota")
            assertThat(error.result!!.steps).hasSize(2)
            assertThat(log.lines.filter { "afterRun" in it }).isEmpty()
        }

        @Test
        fun `the ones that check are those of the agent that answered`() {
            supportModel.answers(listOf(ToolCallPart("call_1", "transfer_to_sales", Json.obj())))
            salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))
            val tripping = OutputGuardrail("tripping") { _, _ -> GuardrailVerdict.Trip("Never") }
            val sales = Agent("sales").model(salesModel).build()

            val result = runner.run(support.handoffs("sales").outputGuardrails(tripping).build(), question) {
                team(sales)
            }

            assertThat(result.text).isEqualTo("Cuesta 100 dólares")
        }
    }

    @Nested
    inner class `On the tools` {
        @Test
        fun `one that trips stops the run before any call of the step runs`() {
            supportModel.answers(listOf(weatherCall, forecastCall))
            val noForecasts = ToolGuardrail("no-forecasts") { call, _ ->
                if (call.toolName == "getForecast") GuardrailVerdict.Trip("No forecasts") else GuardrailVerdict.Pass
            }

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                runner.run(support.toolGuardrails(noForecasts).build(), question)
            }

            assertThat(ran).isEmpty()
            assertThat(error.kind).isEqualTo(GuardrailKinds.Tool)
            assertThat(error.call).isEqualTo(forecastCall)
            assertThat(error.result!!.steps.single().step.response.toolCalls).containsExactly(weatherCall, forecastCall)
            assertThat(error.result!!.newMessages).isEmpty()
        }

        @Test
        fun `one that rejects a call answers it to the model as an error, and the run goes on`() {
            supportModel.answers(listOf(weatherCall, forecastCall), listOf(TextPart("7 grados")))
            val noForecasts = ToolGuardrail("no-forecasts") { call, _ ->
                if (call.toolName == "getForecast") ToolGuardrailVerdict.Reject("Forecasts are not allowed")
                else GuardrailVerdict.Pass
            }

            val result = runner.run(support.toolGuardrails(noForecasts).build(), question)

            assertThat(ran).containsExactly("getWeather")
            assertThat(result.steps[0].step.toolResults[1])
                .isEqualTo(
                    ToolResultPart(
                        "call_2", "getForecast", ToolOutput.Text("Forecasts are not allowed"), isError = true,
                    ),
                )
            assertThat(result.warnings.map { it.message })
                .containsExactly(
                    "The guardrail no-forecasts rejected getForecast on call call_2, which did not run: " +
                        "Forecasts are not allowed",
                )
            assertThat(result.text).isEqualTo("7 grados")
        }

        @Test
        fun `it checks the call as the model made it, before the hooks change it`() {
            supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
            val checked = mutableListOf<JsonObject>()
            val watching = ToolGuardrail("watching") { call, context ->
                checked.add(call.input)
                assertThat(context.agent.name).isEqualTo("support")
                GuardrailVerdict.Pass
            }
            val everywhereIsCordoba = object: AgentHooks {
                override fun beforeTool(call: ToolCallPart, context: AgentToolContext) = Json.obj("city" to "Córdoba")
            }

            runner.run(support.toolGuardrails(watching).hooks(everywhereIsCordoba).build(), question)

            assertThat(checked).containsExactly(Json.obj("city" to "Bariloche"))
        }
    }

    @Nested
    inner class `In a stream` {
        @Test
        fun `with the text held back, nothing of the answer comes out when the output guardrail trips`() {
            supportModel.streams(listOf(StreamPart.TextDelta("Hacen 7 grados, idiota")))
            supportModel.answers(listOf(TextPart("Hacen 7 grados, idiota")))
            val polite = OutputGuardrail("polite") { _, _ -> GuardrailVerdict.Trip("Rude") }
            val events = mutableListOf<RunEvent>()

            runner.stream(support.outputGuardrails(polite).build(), question).use { stream ->
                assertThatThrownBy { stream.forEach { events.add(it) } }.isInstanceOf(GuardrailTrippedError::class.java)
            }

            assertThat(events).containsExactly(RunEvent.StepStarted(1), RunEvent.GuardrailTripped("polite", "Rude"))
        }

        @Test
        fun `with the text held back, the answer comes out all together once the guardrails pass`() {
            supportModel.streams(listOf(StreamPart.TextDelta("Hacen "), StreamPart.TextDelta("7 grados")))
            supportModel.answers(listOf(TextPart("Hacen 7 grados")))
            val checked = mutableListOf<String>()
            val polite = OutputGuardrail("polite") { _, result ->
                checked.add(result.text)
                GuardrailVerdict.Pass
            }
            val seenBeforeTheText = mutableListOf<List<String>>()

            val events = runner.stream(support.outputGuardrails(polite).build(), question).use { stream ->
                stream.asSequence()
                    .onEach { if (it is RunEvent.Model) seenBeforeTheText.add(checked.toList()) }
                    .toList()
            }

            assertThat(seenBeforeTheText).containsOnly(listOf("Hacen 7 grados"))
            assertThat(events).containsExactly(
                RunEvent.StepStarted(1),
                RunEvent.Model(StreamPart.TextDelta("Hacen ")),
                RunEvent.Model(StreamPart.TextDelta("7 grados")),
                RunEvent.StepFinished(1),
            )
        }

        @Test
        fun `the text of a step that calls tools is not held back past the tools`() {
            supportModel.streams(listOf(StreamPart.TextDelta("Me fijo")), listOf(StreamPart.TextDelta("7 grados")))
            supportModel.answers(listOf(TextPart("Me fijo"), weatherCall), listOf(TextPart("7 grados")))
            val passing = OutputGuardrail("passing") { _, _ -> GuardrailVerdict.Pass }

            val events = runner.stream(support.outputGuardrails(passing).build(), question)
                .use { it.asSequence().toList() }

            assertThat(events.take(3)).containsExactly(
                RunEvent.StepStarted(1),
                RunEvent.Model(StreamPart.TextDelta("Me fijo")),
                RunEvent.ToolStarted(weatherCall),
            )
        }

        @Test
        fun `with one that lets the text out, it comes out first and the stream ends telling of the trip`() {
            supportModel.streams(listOf(StreamPart.TextDelta("Hacen 7 grados, idiota")))
            supportModel.answers(listOf(TextPart("Hacen 7 grados, idiota")))
            val polite = OutputGuardrail("polite", holdsText = false) { _, _ -> GuardrailVerdict.Trip("Rude") }
            val events = mutableListOf<RunEvent>()

            runner.stream(support.outputGuardrails(polite).build(), question).use { stream ->
                assertThatThrownBy { stream.forEach { events.add(it) } }.isInstanceOf(GuardrailTrippedError::class.java)
            }

            assertThat(events).containsExactly(
                RunEvent.StepStarted(1),
                RunEvent.Model(StreamPart.TextDelta("Hacen 7 grados, idiota")),
                RunEvent.StepFinished(1),
                RunEvent.GuardrailTripped("polite", "Rude"),
            )
        }

        @Test
        fun `an input guardrail that trips is told by the stream, which calls no model`() {
            val offTopic = InputGuardrail("off-topic") { _, _ -> GuardrailVerdict.Trip("Not the weather") }
            val events = mutableListOf<RunEvent>()

            runner.stream(support.inputGuardrails(offTopic).build(), question).use { stream ->
                assertThatThrownBy { stream.forEach { events.add(it) } }
                    .isInstanceOf(GuardrailTrippedError::class.java)
                    .hasMessageContaining("Not the weather")
            }

            assertThat(events).containsExactly(RunEvent.GuardrailTripped("off-topic", "Not the weather"))
            assertThat(supportModel.requests).isEmpty()
        }

        @Test
        fun `a tool guardrail that trips is told by the stream before any tool starts`() {
            supportModel.answers(listOf(weatherCall))
            val noTools = ToolGuardrail("no-tools") { _, _ -> GuardrailVerdict.Trip("No tools") }
            val events = mutableListOf<RunEvent>()

            runner.stream(support.toolGuardrails(noTools).build(), question).use { stream ->
                assertThatThrownBy { stream.forEach { events.add(it) } }.isInstanceOf(GuardrailTrippedError::class.java)
            }

            assertThat(events.filterNot { it is RunEvent.Model })
                .containsExactly(RunEvent.StepStarted(1), RunEvent.GuardrailTripped("no-tools", "No tools"))
            assertThat(ran).isEmpty()
        }
    }

    @Nested
    inner class `Where they are declared` {
        @Test
        fun `the global ones run first, then those of the agent, then those of the run`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val checked = mutableListOf<String>()
            fun passing(name: String) = InputGuardrail(name) { _, _ ->
                checked.add(name)
                GuardrailVerdict.Pass
            }
            val global = GlobalGuardrails().input(passing("global"))

            val withGlobal = AgentRunner(ModelRegistry(), guardrails = global)

            withGlobal.run(support.inputGuardrails(passing("agent")).build(), question) {
                inputGuardrails(passing("run"))
            }

            assertThat(checked).containsExactly("global", "agent", "run")
        }

        @Test
        fun `the first one that trips wins, and the ones after it are not asked`() {
            val checked = mutableListOf<String>()
            fun tripping(name: String) = InputGuardrail(name) { _, _ ->
                checked.add(name)
                GuardrailVerdict.Trip("No")
            }

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                runner.run(support.inputGuardrails(tripping("first"), tripping("second")).build(), question)
            }

            assertThat(error.guardrail).isEqualTo("first")
            assertThat(checked).containsExactly("first")
        }

        @Test
        fun `a guardrail written as a class is called by the name of its class`() {
            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                runner.run(support.inputGuardrails(Closed()).build(), question)
            }

            assertThat(error.guardrail).isEqualTo("Closed")
        }
    }

    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
    private val forecastCall = ToolCallPart("call_2", "getForecast", Json.obj("city" to "Bariloche"))
    private val ran = mutableListOf<String>()
    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val support = Agent("support")
        .model(supportModel)
        .instructions("Sos soporte")
        .tools(CityTool("getWeather", ran), CityTool("getForecast", ran))
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Que temperatura hay?")

    class Closed: InputGuardrail {
        override fun check(run: AgentHookContext, conversation: List<Message>) = GuardrailVerdict.Trip("We are closed")
    }

    /** A tool about a city that writes down that it ran. */
    class CityTool(
        override val name: String,
        private val ran: MutableList<String>,
    ): Tool<CityTool.Args>(Args.serializer()) {
        override val description = "About the weather of a city"
        override val readOnly = true

        override fun execute(args: Args, context: ToolContext): ToolResult {
            synchronized(ran) { ran.add(name) }
            return ToolResult.text("7 grados en ${args.city}")
        }

        @Serializable
        data class Args(val city: String)
    }
}
