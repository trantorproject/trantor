@file:Suppress("ClassName")

package dev.botta.trantor.ai.telemetry

import dev.botta.json.Json
import dev.botta.trantor.ai.agents.Agent
import dev.botta.trantor.ai.agents.AgentRunner
import dev.botta.trantor.ai.errors.ProviderUnavailableError
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.TestTelemetry
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class GenAIMetricsTest {
    @Nested
    inner class `a call to the model` {
        @Test
        fun `records how long it took, with the buckets of the conventions`() {
            model.responses.add(response(model = "gpt-4.1-mini-2025-04-14"))

            loop().run(ChatRequest("Hola"))

            val metric = telemetry.metric("gen_ai.client.operation.duration")
            val point = metric.histogramData.points.single()
            assertThat(metric.unit).isEqualTo("s")
            assertThat(point.boundaries).containsExactly(
                0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92,
            )
            assertThat(point.count).isEqualTo(1)
            assertThat(point.attributes[OPERATION]).isEqualTo("chat")
            assertThat(point.attributes[PROVIDER]).isEqualTo("openai")
            assertThat(point.attributes[REQUEST_MODEL]).isEqualTo("gpt-4.1-mini")
            assertThat(point.attributes[RESPONSE_MODEL]).isEqualTo("gpt-4.1-mini-2025-04-14")
            assertThat(point.attributes[ERROR_TYPE]).isNull()
        }

        @Test
        fun `that fails records it with the error`() {
            val loop = ToolLoop(FailingModel(ProviderUnavailableError("openai")), emptyList(), openTelemetry = otel)

            assertThatThrownBy { loop.run(ChatRequest("Hola")) }.isInstanceOf(ProviderUnavailableError::class.java)

            val point = histogram("gen_ai.client.operation.duration").single()
            assertThat(point.attributes[ERROR_TYPE]).isEqualTo(ProviderUnavailableError::class.java.name)
        }

        @Test
        fun `counts the tokens it used, in a modality it cannot tell, with the cached ones inside the input`() {
            model.usage = Usage(
                inputTokens = 100,
                outputTokens = 20,
                cacheReadTokens = 40,
                cacheWriteTokens = 10,
                reasoningTokens = 5,
            )

            loop().run(ChatRequest("Hola"))

            assertThat(counted("gen_ai.client.inference.usage.input_tokens")).isEqualTo(100)
            assertThat(counted("gen_ai.client.inference.usage.output_tokens")).isEqualTo(20)
            assertThat(counted("gen_ai.client.inference.usage.cache_read.input_tokens")).isEqualTo(40)
            assertThat(counted("gen_ai.client.inference.usage.cache_write.input_tokens")).isEqualTo(10)
            assertThat(counted("gen_ai.client.inference.usage.reasoning.output_tokens")).isEqualTo(5)
            val point = sum("gen_ai.client.inference.usage.input_tokens").single()
            assertThat(point.attributes[MODALITY]).isEqualTo("unknown")
            assertThat(point.attributes[PROVIDER]).isEqualTo("openai")
        }

        @Test
        fun `without usage counts no tokens`() {
            loop().run(ChatRequest("Hola"))

            assertThat(telemetry.metrics.map { it.name }).noneMatch { it.startsWith("gen_ai.client.inference") }
        }

        @Test
        fun `records how its tokens are spread, one call at a time`() {
            model.usage = Usage(inputTokens = 100, outputTokens = 20)

            loop().run(ChatRequest("Hola"))

            val input = histogram("gen_ai.client.inference.operation.input_tokens").single()
            assertThat(input.sum).isEqualTo(100.0)
            assertThat(input.boundaries).startsWith(1.0, 4.0, 16.0)
            assertThat(histogram("gen_ai.client.inference.operation.output_tokens").single().sum).isEqualTo(20.0)
        }

        @Test
        fun `streamed records how long its first chunk took and each one after it, and only then`() {
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la"), StreamPart.TextDelta("!")))

            loop().stream(ChatRequest("Hola")).use { it.result() }
            loop().run(ChatRequest("Hola"))

            assertThat(histogram("gen_ai.client.operation.time_to_first_chunk").single().count).isEqualTo(1)
            assertThat(histogram("gen_ai.client.operation.time_per_output_chunk").single().count).isEqualTo(2)
            assertThat(histogram("gen_ai.client.operation.duration").single().count).isEqualTo(2)
        }
    }

    @Test
    fun `a tool records how long it took, with its error when it failed`() {
        model.answers(
            listOf(ToolCallPart("call_1", "getWeather", Json.obj()), ToolCallPart("call_2", "broken", Json.obj())),
            listOf(TextPart("Listo")),
        )

        ToolLoop(model, listOf(WeatherTool(), BrokenTool()), openTelemetry = otel).run(ChatRequest("Hola"))

        val points = histogram("gen_ai.execute_tool.duration")
        assertThat(points.single { it.attributes[TOOL_NAME] == "getWeather" }.attributes[ERROR_TYPE]).isNull()
        assertThat(points.single { it.attributes[TOOL_NAME] == "broken" }.attributes[ERROR_TYPE])
            .isEqualTo(IllegalStateException::class.java.name)
        assertThat(points.map { it.attributes[stringKey("gen_ai.tool.type")] }).containsOnly("function")
    }

    @Test
    fun `an agent records how long it had the conversation and what it called itself, and a workflow its length`() {
        model.answers(
            listOf(ToolCallPart("call_1", "getWeather", Json.obj())),
            listOf(ToolCallPart("call_2", "transfer_to_sales", Json.obj())),
        )
        val sales = Agent("sales").model(FakeChatModel(modelId = "sales-model")).build()
        val support = Agent("support").model(model).tools(WeatherTool()).handoffs("sales").build()

        AgentRunner(ModelRegistry(), openTelemetry = otel).run(support, Message.user("Hola")) { team(sales) }

        val durations = histogram("gen_ai.invoke_agent.duration")
        assertThat(durations.map { it.attributes[AGENT_NAME] }).containsExactlyInAnyOrder("support", "sales")
        assertThat(durations.single { it.attributes[AGENT_NAME] == "sales" }.attributes[REQUEST_MODEL])
            .isEqualTo("sales-model")
        assertThat(recorded("gen_ai.invoke_agent.inference_calls"))
            .containsEntry("support", 2.0)
            .containsEntry("sales", 1.0)
        assertThat(recorded("gen_ai.invoke_agent.tool_calls")).containsEntry("support", 2.0).containsEntry("sales", 0.0)
        val workflow = histogram("gen_ai.invoke_workflow.duration").single()
        assertThat(workflow.attributes[stringKey("gen_ai.workflow.name")]).isEqualTo("support")
    }

    @Test
    fun `a telemetry that fails to measure does not fail the generation`() {
        model.answers(listOf(ToolCallPart("call_1", "getWeather", Json.obj())), listOf(TextPart("Hacen 7 grados")))

        val result = ToolLoop(model, listOf(WeatherTool()), openTelemetry = BrokenMeters).run(ChatRequest("Hola"))

        assertThat(result.text).isEqualTo("Hacen 7 grados")
    }

    private fun histogram(name: String): Collection<HistogramPointData> = telemetry.metric(name).histogramData.points

    private fun sum(name: String): Collection<LongPointData> = telemetry.metric(name).longSumData.points

    private fun counted(name: String) = sum(name).sumOf { it.value }

    /** The sum of each agent, by its name. */
    private fun recorded(name: String) = histogram(name).associate { it.attributes[AGENT_NAME] to it.sum }

    private fun loop() = ToolLoop(model, emptyList(), openTelemetry = otel)

    private fun response(model: String) = ChatResponse(
        listOf(TextPart("Hola")),
        FinishReasons.Stop,
        ResponseInfo(model = model, provider = "openai", latency = 1.milliseconds),
    )

    private val telemetry = TestTelemetry()
    private val otel = telemetry.openTelemetry
    private val model = FakeChatModel(modelId = "gpt-4.1-mini", provider = "openai")

    private class FailingModel(private val error: Throwable): ChatModel {
        override val modelId = "gpt-4.1-mini"
        override val provider = "openai"

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse = throw error

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream = throw error
    }

    private class WeatherTool: Tool<NoArgs>() {
        override val name = "getWeather"
        override val description = "The weather"

        override fun execute(args: NoArgs, context: ToolContext) = ToolResult.text("7 grados")
    }

    private class BrokenTool: Tool<NoArgs>() {
        override val name = "broken"
        override val description = "Always fails"

        override fun execute(args: NoArgs, context: ToolContext): ToolResult = error("db down")
    }

    @Serializable
    class NoArgs

    /** An OpenTelemetry whose every meter fails, as a broken instrumentation of the application might. */
    private object BrokenMeters: OpenTelemetry {
        override fun getTracerProvider(): TracerProvider = TracerProvider.noop()

        override fun getMeterProvider(): MeterProvider = MeterProvider { throw IllegalStateException("broken") }

        override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
    }

    private companion object {
        val OPERATION = stringKey("gen_ai.operation.name")
        val PROVIDER = stringKey("gen_ai.provider.name")
        val REQUEST_MODEL = stringKey("gen_ai.request.model")
        val RESPONSE_MODEL = stringKey("gen_ai.response.model")
        val ERROR_TYPE = stringKey("error.type")
        val MODALITY = stringKey("gen_ai.token.modality")
        val TOOL_NAME = stringKey("gen_ai.tool.name")
        val AGENT_NAME = stringKey("gen_ai.agent.name")
    }
}
