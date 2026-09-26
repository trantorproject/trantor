@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.ProviderUnavailableError
import dev.botta.trantor.ai.generation.ToolLoopTest.WeatherTool
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.TestTelemetry
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.doubleKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringArrayKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.propagation.ContextPropagators
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import io.opentelemetry.api.common.AttributeKey.booleanKey
import org.slf4j.MDC
import java.util.Collections

class ToolLoopTracingTest {
    @Nested
    inner class `a generation` {
        @Test
        fun `is an invoke_agent span with a chat span for its call to the model`() {
            loop().run(ChatRequest("Hola"))

            val generation = telemetry.named("invoke_agent")
            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(generation.kind).isEqualTo(SpanKind.INTERNAL)
            assertThat(generation.attributes[stringKey("gen_ai.operation.name")]).isEqualTo("invoke_agent")
            assertThat(chat.kind).isEqualTo(SpanKind.CLIENT)
            assertThat(chat.parentSpanId).isEqualTo(generation.spanId)
        }

        @Test
        fun `with a tool, has the calls to the model and the tool side by side, in the order they happened`() {
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

            val generation = telemetry.named("invoke_agent")
            val steps = telemetry.spans.filter { it.parentSpanId == generation.spanId }.sortedBy { it.startEpochNanos }
            assertThat(steps.map { it.name })
                .containsExactly("chat gpt-4.1-mini", "execute_tool getWeather", "chat gpt-4.1-mini")
        }

        @Test
        fun `hangs from the span that was current when it was called, as the one of a request`() {
            val request = telemetry.tracer.spanBuilder("POST /chats").startSpan()

            request.makeCurrent().use { loop().run(ChatRequest("Hola")) }
            request.end()

            assertThat(telemetry.named("invoke_agent").parentSpanId).isEqualTo(request.spanContext.spanId)
        }

        @Test
        fun `makes the chat span current while the model works, so the http call of the provider is inside it`() {
            val watching = SpanWatchingModel(model)

            ToolLoop(watching, listOf(weather), openTelemetry = telemetry.openTelemetry).run(ChatRequest("Hola"))

            assertThat(watching.current).isEqualTo(telemetry.named("chat gpt-4.1-mini").spanContext)
        }

        @Test
        fun `and the execute_tool span while the tool runs`() {
            var current: SpanContext? = null
            weather.onExecute = { current = Span.current().spanContext }
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

            assertThat(current).isEqualTo(telemetry.named("execute_tool getWeather").spanContext)
        }

        @Test
        fun `adds up the usage of every call to the model`() {
            model.usage = Usage(inputTokens = 100, outputTokens = 20)
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

            val generation = telemetry.named("invoke_agent")
            assertThat(generation.attributes[longKey("gen_ai.usage.input_tokens")]).isEqualTo(200)
            assertThat(generation.attributes[longKey("gen_ai.usage.output_tokens")]).isEqualTo(40)
        }
    }

    @Nested
    inner class `a chat span` {
        @Test
        fun `says which provider and model were asked, with the settings of the request`() {
            val settings =
                ChatSettings(maxOutputTokens = 500, temperature = 0.2, topP = 0.9, stopSequences = listOf("FIN"))

            loop().run(ChatRequest(listOf(Message.user("Hola")), settings = settings))

            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(chat.attributes[stringKey("gen_ai.operation.name")]).isEqualTo("chat")
            assertThat(chat.attributes[stringKey("gen_ai.provider.name")]).isEqualTo("openai")
            assertThat(chat.attributes[stringKey("gen_ai.request.model")]).isEqualTo("gpt-4.1-mini")
            assertThat(chat.attributes[longKey("gen_ai.request.max_tokens")]).isEqualTo(500)
            assertThat(chat.attributes[doubleKey("gen_ai.request.temperature")]).isEqualTo(0.2)
            assertThat(chat.attributes[doubleKey("gen_ai.request.top_p")]).isEqualTo(0.9)
            assertThat(chat.attributes[stringArrayKey("gen_ai.request.stop_sequences")]).containsExactly("FIN")
        }

        @Test
        fun `leaves out the settings the request did not set`() {
            loop().run(ChatRequest("Hola"))

            val keys = telemetry.named("chat gpt-4.1-mini").attributes.asMap().keys.map { it.key }
            assertThat(keys)
                .doesNotContain("gen_ai.request.temperature", "gen_ai.request.max_tokens", "gen_ai.output.type")
        }

        @Test
        fun `says the output is json when the request asks for an object`() {
            loop().run(ChatRequest(listOf(Message.user("Hola")), output = OutputSpec.Json(Json.obj())))

            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(chat.attributes[stringKey("gen_ai.output.type")]).isEqualTo("json")
        }

        @Test
        fun `says what answered, why it stopped and what it used, with the cached tokens inside the input`() {
            model.responses.add(
                ChatResponse(
                    content = listOf(TextPart("Hola")),
                    finishReason = FinishReasons.Length,
                    info = ResponseInfo("resp_7", "gpt-4.1-mini-2025-04-14", "openai", 1.milliseconds),
                    usage = Usage(
                        inputTokens = 100,
                        outputTokens = 20,
                        cacheReadTokens = 40,
                        cacheWriteTokens = 10,
                        reasoningTokens = 5,
                    ),
                )
            )

            loop().run(ChatRequest("Hola"))

            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(chat.attributes[stringKey("gen_ai.response.id")]).isEqualTo("resp_7")
            assertThat(chat.attributes[stringKey("gen_ai.response.model")]).isEqualTo("gpt-4.1-mini-2025-04-14")
            assertThat(chat.attributes[stringArrayKey("gen_ai.response.finish_reasons")]).containsExactly("length")
            assertThat(chat.attributes[longKey("gen_ai.usage.input_tokens")]).isEqualTo(100)
            assertThat(chat.attributes[longKey("gen_ai.usage.output_tokens")]).isEqualTo(20)
            assertThat(chat.attributes[longKey("gen_ai.usage.cache_read.input_tokens")]).isEqualTo(40)
            assertThat(chat.attributes[longKey("gen_ai.usage.cache_write.input_tokens")]).isEqualTo(10)
            assertThat(chat.attributes[longKey("gen_ai.usage.reasoning.output_tokens")]).isEqualTo(5)
        }

        @Test
        fun `says a model that called tools stopped for them`() {
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

            val first = telemetry.spans.filter { it.name == "chat gpt-4.1-mini" }.minBy { it.startEpochNanos }
            assertThat(first.attributes[stringArrayKey("gen_ai.response.finish_reasons")]).containsExactly("tool_calls")
        }
    }

    @Nested
    inner class `an execute_tool span` {
        @Test
        fun `says which tool ran and for which call`() {
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

            val tool = telemetry.named("execute_tool getWeather")
            assertThat(tool.kind).isEqualTo(SpanKind.INTERNAL)
            assertThat(tool.attributes[stringKey("gen_ai.operation.name")]).isEqualTo("execute_tool")
            assertThat(tool.attributes[stringKey("gen_ai.tool.name")]).isEqualTo("getWeather")
            assertThat(tool.attributes[stringKey("gen_ai.tool.call.id")]).isEqualTo("call_1")
            assertThat(tool.attributes[stringKey("gen_ai.tool.type")]).isEqualTo("function")
            assertThat(tool.attributes[stringKey("gen_ai.tool.description")]).isEqualTo("The current weather of a city")
        }
    }

    @Nested
    inner class `a failure` {
        @Test
        fun `of the model marks its chat and the generation, and comes out as it was`() {
            val down = ProviderUnavailableError("openai")

            val loop = ToolLoop(FailingModel(down), emptyList(), openTelemetry = telemetry.openTelemetry)

            assertThatThrownBy { loop.run(ChatRequest("Hola")) }.isSameAs(down)

            listOf("chat gpt-4.1-mini", "invoke_agent").forEach { name ->
                val span = telemetry.named(name)
                assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
                assertThat(span.attributes[stringKey("error.type")])
                    .isEqualTo(ProviderUnavailableError::class.java.name)
            }
        }

        @Test
        fun `of a tool marks its execute_tool alone, since the model reads it and the run goes on`() {
            model.answers(listOf(ToolCallPart("call_1", "broken", Json.obj())), listOf(TextPart("No pude")))

            ToolLoop(model, listOf(BrokenTool()), openTelemetry = telemetry.openTelemetry).run(ChatRequest("Hola"))

            val tool = telemetry.named("execute_tool broken")
            assertThat(tool.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(tool.attributes[stringKey("error.type")]).isEqualTo(IllegalStateException::class.java.name)
            assertThat(telemetry.named("invoke_agent").status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `of the telemetry itself does not fail the generation`() {
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

            val result = ToolLoop(model, listOf(weather), openTelemetry = BrokenTelemetry).run(ChatRequest("Hola"))

            assertThat(result.text).isEqualTo("Hacen 7 grados")
        }
    }

    @Nested
    inner class `tools at the same time` {
        @Test
        fun `hang from the generation, and what each one does hangs from its own span`() {
            model.answers(twoCityCalls(), listOf(TextPart("Frio")))

            ToolLoop(model, listOf(cities), openTelemetry = telemetry.openTelemetry).run(ChatRequest("Hola"))

            val generation = telemetry.named("invoke_agent")
            val tools = telemetry.spans.filter { it.name == "execute_tool getCity" }
            assertThat(tools).hasSize(2).allMatch { it.parentSpanId == generation.spanId }
            assertThat(cities.currentSpans).containsExactlyInAnyOrderElementsOf(tools.map { it.spanContext })
        }

        @Test
        fun `log with the correlation id of whoever started the generation`() {
            model.answers(twoCityCalls(), listOf(TextPart("Frio")))

            try {
                MDC.put("cid", "req-7")
                ToolLoop(model, listOf(cities)).run(ChatRequest("Hola"))
            } finally {
                MDC.remove("cid")
            }

            assertThat(cities.correlationIds).containsExactly("req-7", "req-7")
        }
    }

    @Nested
    inner class `a stream` {
        @Test
        fun `read to its end leaves what a run leaves, and says it was streamed`() {
            model.usage = Usage(inputTokens = 100, outputTokens = 20)
            model.streams(listOf(StreamPart.PartDone(weatherCall())), listOf(StreamPart.TextDelta("7 grados")))
            model.answers(listOf(weatherCall()), listOf(TextPart("7 grados")))

            loop().stream(ChatRequest("Que temperatura hay?")).use { it.result() }

            val generation = telemetry.named("invoke_agent")
            val steps = telemetry.spans.filter { it.parentSpanId == generation.spanId }.sortedBy { it.startEpochNanos }
            assertThat(steps.map { it.name })
                .containsExactly("chat gpt-4.1-mini", "execute_tool getWeather", "chat gpt-4.1-mini")
            val chat = steps.first()
            assertThat(chat.attributes[booleanKey("gen_ai.request.stream")]).isTrue()
            assertThat(chat.attributes[stringArrayKey("gen_ai.response.finish_reasons")]).containsExactly("tool_calls")
            assertThat(chat.attributes[longKey("gen_ai.usage.input_tokens")]).isEqualTo(100)
            assertThat(generation.attributes[longKey("gen_ai.usage.input_tokens")]).isEqualTo(200)
        }

        @Test
        fun `says how long the first chunk took`() {
            model.streams(listOf(StreamPart.TextDelta("Hola")))

            loop().stream(ChatRequest("Hola")).use { it.result() }

            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(chat.attributes[doubleKey("gen_ai.response.time_to_first_chunk")]).isNotNull().isNotNegative()
        }

        @Test
        fun `hangs from the span that was current when it was asked for, wherever it is read`() {
            val request = telemetry.tracer.spanBuilder("POST /chats").startSpan()
            val stream = request.makeCurrent().use { loop().stream(ChatRequest("Hola")) }

            val reader = Thread { stream.use { it.result() } }.apply { start() }
            reader.join()
            request.end()

            assertThat(telemetry.named("invoke_agent").parentSpanId).isEqualTo(request.spanContext.spanId)
        }

        @Test
        fun `leaves the span of whoever reads it current between one event and the next`() {
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))
            val reader = telemetry.tracer.spanBuilder("sse").startSpan()
            val seen = mutableListOf<SpanContext>()

            reader.makeCurrent().use {
                loop().stream(ChatRequest("Hola")).use { stream ->
                    stream.forEach { _ -> seen.add(Span.current().spanContext) }
                }
            }
            reader.end()

            assertThat(seen).isNotEmpty().containsOnly(reader.spanContext)
        }

        @Test
        fun `makes the chat span current while the model opens its stream, so the http call is inside it`() {
            val watching = SpanWatchingModel(model)

            ToolLoop(watching, emptyList(), openTelemetry = telemetry.openTelemetry)
                .stream(ChatRequest("Hola"))
                .use { it.result() }

            assertThat(watching.current).isEqualTo(telemetry.named("chat gpt-4.1-mini").spanContext)
        }

        @Test
        fun `closed halfway ends every span it opened, without failing, and the chat never got its finish reason`() {
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))

            loop().stream(ChatRequest("Hola")).use { stream ->
                stream.next()
                stream.next()
            }

            val chat = telemetry.named("chat gpt-4.1-mini")
            assertThat(chat.status.statusCode).isEqualTo(StatusCode.UNSET)
            assertThat(chat.attributes[stringArrayKey("gen_ai.response.finish_reasons")]).containsExactly("error")
            assertThat(telemetry.named("invoke_agent").status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `whose model fails halfway marks the chat and the generation`() {
            val broken = BrokenStreamModel(ProviderUnavailableError("openai"))

            assertThatThrownBy {
                ToolLoop(broken, emptyList(), openTelemetry = telemetry.openTelemetry)
                    .stream(ChatRequest("Hola"))
                    .use { it.result() }
            }.isInstanceOf(ProviderUnavailableError::class.java)

            listOf("chat gpt-4.1-mini", "invoke_agent").forEach { name ->
                assertThat(telemetry.named(name).status.statusCode).isEqualTo(StatusCode.ERROR)
            }
        }
    }

    private fun loop() = ToolLoop(model, listOf(weather), openTelemetry = telemetry.openTelemetry)

    private fun weatherCall() = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))

    private fun twoCityCalls() = listOf(
        ToolCallPart("call_1", "getCity", Json.obj("city" to "Bariloche")),
        ToolCallPart("call_2", "getCity", Json.obj("city" to "Ushuaia")),
    )

    private val telemetry = TestTelemetry()
    private val model = FakeChatModel(modelId = "gpt-4.1-mini", provider = "openai")
    private val weather = WeatherTool()
    private val cities = CityTool()

    /** Keeps the span that was current while it was asked, and answers as [model] does. */
    private class SpanWatchingModel(private val model: ChatModel): ChatModel by model {
        var current: SpanContext? = null

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
            current = Span.current().spanContext

            return model.generate(request, options)
        }

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
            current = Span.current().spanContext

            return model.stream(request, options)
        }
    }

    /** Opens its stream, gives one part and fails, as a connection that drops in the middle of an answer. */
    private class BrokenStreamModel(private val error: Throwable): ChatModel {
        override val modelId = "gpt-4.1-mini"
        override val provider = "openai"

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse = throw error

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream = object: ChatStream {
            private var given = false

            override fun hasNext() = true

            override fun next(): StreamPart {
                if (given) throw error
                given = true
                return StreamPart.TextDelta("Ho")
            }

            override fun response(): ChatResponse = throw error

            override fun close() {}
        }
    }

    /** A tool that only reads, so two calls of a step run at the same time, each one on its own thread. */
    private class CityTool: Tool<CityTool.Args>(Args.serializer()) {
        override val name = "getCity"
        override val description = "A city"
        override val readOnly = true

        val currentSpans: MutableList<SpanContext> = Collections.synchronizedList(mutableListOf())
        val correlationIds: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        override fun execute(args: Args, context: ToolContext): ToolResult {
            currentSpans.add(Span.current().spanContext)
            correlationIds.add(MDC.get("cid"))

            return ToolResult.text("Frio en ${args.city}")
        }

        @Serializable
        data class Args(val city: String)
    }

    private class FailingModel(private val error: Throwable): ChatModel {
        override val modelId = "gpt-4.1-mini"
        override val provider = "openai"

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse = throw error

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream = throw error
    }

    private class BrokenTool: Tool<BrokenTool.Args>(Args.serializer()) {
        override val name = "broken"
        override val description = "Always fails"

        override fun execute(args: Args, context: ToolContext): ToolResult = error("db down")

        @Serializable
        class Args
    }

    /** An OpenTelemetry whose every span fails to start, as a broken instrumentation of the application might. */
    private object BrokenTelemetry: OpenTelemetry {
        private val tracer = Tracer { throw IllegalStateException("The tracer is broken") }

        override fun getTracerProvider() = object: TracerProvider {
            override fun get(instrumentationScopeName: String) = tracer

            override fun get(instrumentationScopeName: String, instrumentationScopeVersion: String) = tracer
        }

        override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
    }
}
