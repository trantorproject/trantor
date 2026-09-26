@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.ProviderUnavailableError
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.TestTelemetry
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentRunnerTracingTest {
    @Nested
    inner class `an agent alone` {
        @Test
        fun `is an invoke_agent span named after it, with its calls to the model and its tools inside`() {
            supportModel.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            runner.run(support.tools(weather).build(), question)

            val run = telemetry.named("invoke_agent support")
            assertThat(namesOf(childrenOf(run)))
                .containsExactly("chat support-model", "execute_tool getWeather", "chat support-model")
            assertThat(childrenOf(run).map { it.attributes[AGENT_NAME] }).containsOnly("support")
            assertThat(telemetry.spans.map { it.name }).noneMatch { it.startsWith("invoke_workflow") }
        }

        @Test
        fun `says its name, its model and what its run used`() {
            supportModel.usage = Usage(inputTokens = 100, outputTokens = 20)
            supportModel.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            runner.run(support.tools(weather).build(), question)

            val run = telemetry.named("invoke_agent support")
            assertThat(run.attributes[OPERATION]).isEqualTo("invoke_agent")
            assertThat(run.attributes[AGENT_NAME]).isEqualTo("support")
            assertThat(run.attributes[REQUEST_MODEL]).isEqualTo("support-model")
            assertThat(run.attributes[INPUT_TOKENS]).isEqualTo(200)
            assertThat(run.attributes[OUTPUT_TOKENS]).isEqualTo(40)
        }

        @Test
        fun `hangs from the span that was current when it was called, as the one of a request`() {
            val request = telemetry.tracer.spanBuilder("POST /chats").startSpan()

            request.makeCurrent().use { runner.run(support.build(), question) }
            request.end()

            assertThat(telemetry.named("invoke_agent support").parentSpanId).isEqualTo(request.spanContext.spanId)
        }
    }

    @Nested
    inner class `a team` {
        @Test
        fun `is an invoke_workflow span with an invoke_agent span for each agent that had the conversation`() {
            supportModel.answers(listOf(call("call_1", "transfer_to_sales")))
            salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

            runner.run(support.handoffs("sales").build(), question) { team(sales) }

            val workflow = telemetry.named("invoke_workflow support")
            assertThat(namesOf(childrenOf(workflow))).containsExactly("invoke_agent support", "invoke_agent sales")
            assertThat(namesOf(childrenOf(telemetry.named("invoke_agent support"))))
                .containsExactly("chat support-model", "execute_tool transfer_to_sales")
            assertThat(namesOf(childrenOf(telemetry.named("invoke_agent sales")))).containsExactly("chat sales-model")
        }

        @Test
        fun `is a workflow even when nobody handed the conversation over, so its shape is the application's`() {
            runner.run(support.handoffs("sales").build(), question) { team(sales) }

            val workflow = telemetry.named("invoke_workflow support")
            assertThat(namesOf(childrenOf(workflow))).containsExactly("invoke_agent support")
        }

        @Test
        fun `says the name of the agent it starts with and what the whole run used, and each agent what it used`() {
            supportModel.usage = Usage(inputTokens = 100, outputTokens = 20)
            salesModel.usage = Usage(inputTokens = 300, outputTokens = 50)
            supportModel.answers(listOf(call("call_1", "transfer_to_sales")))

            runner.run(support.handoffs("sales").build(), question) { team(sales) }

            val workflow = telemetry.named("invoke_workflow support")
            assertThat(workflow.attributes[OPERATION]).isEqualTo("invoke_workflow")
            assertThat(workflow.attributes[stringKey("gen_ai.workflow.name")]).isEqualTo("support")
            assertThat(workflow.attributes[INPUT_TOKENS]).isEqualTo(400)
            val salesSpan = telemetry.named("invoke_agent sales")
            assertThat(salesSpan.attributes[AGENT_NAME]).isEqualTo("sales")
            assertThat(salesSpan.attributes[REQUEST_MODEL]).isEqualTo("sales-model")
            assertThat(salesSpan.attributes[INPUT_TOKENS]).isEqualTo(300)
            assertThat(telemetry.named("invoke_agent support").attributes[INPUT_TOKENS]).isEqualTo(100)
        }

        @Test
        fun `whose model fails marks its chat, its agent and the workflow, and the error comes out as it was`() {
            val down = ProviderUnavailableError("sales")
            supportModel.answers(listOf(call("call_1", "transfer_to_sales")))
            val failingSales = Agent("sales").model(FailingModel(down)).build()

            assertThatThrownBy { runner.run(support.handoffs("sales").build(), question) { team(failingSales) } }
                .isSameAs(down)

            listOf("chat sales-model", "invoke_agent sales", "invoke_workflow support").forEach { name ->
                assertThat(telemetry.named(name).status.statusCode).isEqualTo(StatusCode.ERROR)
                assertThat(telemetry.named(name).attributes[ERROR_TYPE])
                    .isEqualTo(ProviderUnavailableError::class.java.name)
            }
            assertThat(telemetry.named("invoke_agent support").status.statusCode).isEqualTo(StatusCode.UNSET)
        }
    }

    @Nested
    inner class `an agent used as a tool` {
        @Test
        fun `runs inside the execute_tool that called it, never as a workflow, even with a team of its own`() {
            val researcherModel = FakeChatModel(modelId = "researcher-model")
            val researcher = Agent("researcher").model(researcherModel).handoffs("sales").build()
            val writer = support.tools(researcher.asTool(runner, "Investiga") { team(sales) }).build()
            supportModel.answers(listOf(call("call_1", "researcher", Json.obj("task" to "El clima"))))

            runner.run(writer, question)

            val tool = telemetry.named("execute_tool researcher")
            assertThat(namesOf(childrenOf(tool))).containsExactly("invoke_agent researcher")
            assertThat(telemetry.spans.map { it.name }).noneMatch { it.startsWith("invoke_workflow") }
        }
    }

    @Nested
    inner class `a guardrail` {
        @Test
        fun `of the input is a run_guardrail span under the run, before the first call to the model`() {
            runner.run(support.inputGuardrails(passing("onTopic")).build(), question)

            val guardrail = telemetry.named("run_guardrail onTopic")
            assertThat(namesOf(childrenOf(telemetry.named("invoke_agent support"))))
                .containsExactly("run_guardrail onTopic", "chat support-model")
            assertThat(guardrail.attributes[OPERATION]).isEqualTo("run_guardrail")
            assertThat(guardrail.attributes[TARGET_TYPE]).isEqualTo("input")
            assertThat(guardrail.attributes[TARGET_SUBTYPE]).isEqualTo("llm")
            assertThat(guardrail.attributes[VERDICT]).isEqualTo("allow")
            assertThat(guardrail.attributes[ACTION]).isEqualTo("allow")
        }

        @Test
        fun `that trips says why and that it blocked, and it is the run that failed, not the guardrail`() {
            val closed = InputGuardrail("closed") { _, _ -> GuardrailVerdict.Trip("We are closed") }

            assertThatThrownBy { runner.run(support.inputGuardrails(closed).build(), question) }
                .isInstanceOf(GuardrailTrippedError::class.java)

            val guardrail = telemetry.named("run_guardrail closed")
            assertThat(guardrail.attributes[VERDICT]).isEqualTo("deny")
            assertThat(guardrail.attributes[ACTION]).isEqualTo("block")
            assertThat(guardrail.attributes[REASON]).isEqualTo("We are closed")
            assertThat(guardrail.status.statusCode).isEqualTo(StatusCode.UNSET)
            val run = telemetry.named("invoke_agent support")
            assertThat(run.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(run.attributes[ERROR_TYPE]).isEqualTo(GuardrailTrippedError::class.java.name)
            assertThat(telemetry.spans.map { it.name }).noneMatch { it.startsWith("chat") }
        }

        @Test
        fun `of the output comes after the last call to the model, and when it trips the run fails`() {
            val polite = OutputGuardrail("polite") { _, _ -> GuardrailVerdict.Trip("Rude") }

            assertThatThrownBy { runner.run(support.outputGuardrails(polite).build(), question) }
                .isInstanceOf(GuardrailTrippedError::class.java)

            val run = telemetry.named("invoke_agent support")
            assertThat(namesOf(childrenOf(run))).containsExactly("chat support-model", "run_guardrail polite")
            assertThat(telemetry.named("run_guardrail polite").attributes[TARGET_TYPE]).isEqualTo("output")
            assertThat(telemetry.named("chat support-model").status.statusCode).isEqualTo(StatusCode.UNSET)
            assertThat(run.status.statusCode).isEqualTo(StatusCode.ERROR)
        }

        @Test
        fun `of a tool is one span for each call, and one that rejects it leaves the run going`() {
            val noWeather = ToolGuardrail("noWeather") { _, _ -> ToolGuardrailVerdict.Reject("Not today") }
            supportModel.answers(listOf(weatherCall()), listOf(TextPart("No puedo")))

            runner.run(support.tools(weather).toolGuardrails(noWeather).build(), question)

            val guardrail = telemetry.named("run_guardrail noWeather")
            assertThat(guardrail.attributes[TARGET_TYPE]).isEqualTo("input")
            assertThat(guardrail.attributes[TARGET_SUBTYPE]).isEqualTo("tool_call")
            assertThat(guardrail.attributes[stringKey("gen_ai.guardrail.target.id")]).isEqualTo("call_1")
            assertThat(guardrail.attributes[VERDICT]).isEqualTo("deny")
            assertThat(guardrail.attributes[REASON]).isEqualTo("Not today")
            assertThat(guardrail.parentSpanId).isEqualTo(telemetry.named("invoke_agent support").spanId)
            assertThat(telemetry.named("invoke_agent support").status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `of a tool that trips fails the agent that asked for the call and the workflow`() {
            val stop = ToolGuardrail("stop") { _, _ -> GuardrailVerdict.Trip("Never") }
            supportModel.answers(listOf(weatherCall()))

            assertThatThrownBy {
                runner.run(support.tools(weather).toolGuardrails(stop).handoffs("sales").build(), question) {
                    team(sales)
                }
            }.isInstanceOf(GuardrailTrippedError::class.java)

            assertThat(telemetry.named("run_guardrail stop").parentSpanId)
                .isEqualTo(telemetry.named("invoke_agent support").spanId)
            assertThat(telemetry.named("invoke_agent support").status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(telemetry.named("invoke_workflow support").status.statusCode).isEqualTo(StatusCode.ERROR)
        }

        @Test
        fun `that throws is what failed`() {
            val broken = InputGuardrail("broken") { _, _ -> error("The classifier is down") }

            assertThatThrownBy { runner.run(support.inputGuardrails(broken).build(), question) }
                .isInstanceOf(IllegalStateException::class.java)

            val guardrail = telemetry.named("run_guardrail broken")
            assertThat(guardrail.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(guardrail.attributes[ERROR_TYPE]).isEqualTo(IllegalStateException::class.java.name)
        }

        @Test
        fun `that calls a model has the spans of that call inside its own`() {
            val classifier =
                ToolLoop(FakeChatModel(modelId = "classifier"), emptyList(), openTelemetry = telemetry.openTelemetry)
            val onTopic = InputGuardrail("onTopic") { _, conversation ->
                classifier.run(ChatRequest(conversation))
                GuardrailVerdict.Pass
            }

            runner.run(support.inputGuardrails(onTopic).build(), question)

            val generation = telemetry.spans.single { it.name == "invoke_agent" }
            assertThat(generation.parentSpanId).isEqualTo(telemetry.named("run_guardrail onTopic").spanId)
        }
    }

    private fun childrenOf(parent: SpanData) =
        telemetry.spans.filter { it.parentSpanId == parent.spanId }.sortedBy { it.startEpochNanos }

    private fun namesOf(spans: List<SpanData>) = spans.map { it.name }

    private fun passing(name: String) = InputGuardrail(name) { _, _ -> GuardrailVerdict.Pass }

    private fun weatherCall() = call("call_1", "getWeather")

    private fun call(callId: String, tool: String, input: JsonObject = Json.obj()) = ToolCallPart(callId, tool, input)

    private val telemetry = TestTelemetry()
    private val runner = AgentRunner(ModelRegistry(), openTelemetry = telemetry.openTelemetry)
    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val weather = WeatherTool()
    private val support = Agent("support").model(supportModel)
    private val sales = Agent("sales").model(salesModel).build()
    private val question = Message.user("Cuanto sale?")

    private class FailingModel(private val error: Throwable): ChatModel {
        override val modelId = "sales-model"
        override val provider = "fake"

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse = throw error

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream = throw error
    }

    private class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The weather"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("7 grados")

        @Serializable
        class Args
    }

    private companion object {
        val OPERATION = stringKey("gen_ai.operation.name")
        val AGENT_NAME = stringKey("gen_ai.agent.name")
        val REQUEST_MODEL = stringKey("gen_ai.request.model")
        val INPUT_TOKENS = longKey("gen_ai.usage.input_tokens")
        val OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens")
        val ERROR_TYPE = stringKey("error.type")
        val TARGET_TYPE = stringKey("gen_ai.guardrail.target.type")
        val TARGET_SUBTYPE = stringKey("gen_ai.guardrail.target.subtype")
        val VERDICT = stringKey("gen_ai.guardrail.verdict.type")
        val ACTION = stringKey("gen_ai.guardrail.action.type")
        val REASON = stringKey("gen_ai.guardrail.verdict.reason")
    }
}
