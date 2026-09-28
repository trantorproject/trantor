@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.TextPart
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.testing.TestTelemetry
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the traces show of the requests to an MCP server, as the conventions of OpenTelemetry for MCP ask (in
 * Development, like the ones of GenAI): a client span for each request, or the MCP attributes on the span of the tool
 * when an agent calls it, and the context of the trace in `_meta` for the server to continue it.
 */
class McpTracingTest {
    @Nested
    inner class `a request` {
        @Test
        fun `goes in a client span named after its method, with the MCP attributes`() {
            httpClient.answer(fixture("tools-list.json"))

            client.listTools()

            val span = telemetry.named("tools/list")
            assertThat(span.kind).isEqualTo(SpanKind.CLIENT)
            assertThat(span.attributes.get(stringKey("mcp.method.name"))).isEqualTo("tools/list")
            assertThat(span.attributes.get(stringKey("mcp.protocol.version"))).isEqualTo("2026-07-28")
            assertThat(span.attributes.get(stringKey("jsonrpc.request.id"))).isEqualTo("1")
            assertThat(span.attributes.get(stringKey("network.transport"))).isEqualTo("tcp")
            assertThat(span.attributes.get(stringKey("server.address"))).isEqualTo("127.0.0.1")
            assertThat(span.attributes.get(longKey("server.port"))).isEqualTo(3001L)
        }

        @Test
        fun `carries the context of its span in _meta, for the server to continue the trace`() {
            httpClient.answer(fixture("tools-list.json"))

            client.listTools()

            val span = telemetry.named("tools/list")
            val traceparent = sentBody().path("params._meta.traceparent")?.asString()
            assertThat(traceparent).startsWith("00-${span.traceId}-${span.spanId}-")
        }

        @Test
        fun `turned down by the server says its code as error_type and rpc_response_status_code`() {
            httpClient.answer(fixture("call-unknown-tool.json"))

            assertThatThrownBy { client.callTool("launch") }

            val span = telemetry.named("tools/call launch")
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.status.description).isEqualTo("Tool launch not found")
            assertThat(span.attributes.get(stringKey("error.type"))).isEqualTo("-32602")
            assertThat(span.attributes.get(stringKey("rpc.response.status_code"))).isEqualTo("-32602")
        }
    }

    @Nested
    inner class `a tool` {
        @Test
        fun `called directly has a span named after it`() {
            httpClient.answer(fixture("call-weather.json"))

            client.callTool("get_weather", Json.obj("city" to "Rosario"))

            val span = telemetry.named("tools/call get_weather")
            assertThat(span.kind).isEqualTo(SpanKind.CLIENT)
            assertThat(span.attributes.get(stringKey("gen_ai.tool.name"))).isEqualTo("get_weather")
            assertThat(span.attributes.get(stringKey("gen_ai.operation.name"))).isEqualTo("execute_tool")
        }

        @Test
        fun `that failed is a tool_error`() {
            httpClient.answer(fixture("call-divide-by-zero.json"))

            client.callTool("divide", Json.obj("a" to 1, "b" to 0))

            val span = telemetry.named("tools/call divide")
            assertThat(span.attributes.get(stringKey("error.type"))).isEqualTo("tool_error")
        }

        @Test
        fun `of an agent puts the MCP attributes on its execute_tool span, without a span of its own`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-weather.json"))
            val model = FakeChatModel().answers(
                listOf(ToolCallPart("call_1", "tester_get_weather", Json.obj("city" to "Rosario"))),
                listOf(TextPart("It is 18 degrees")),
            )
            val tools = client.tools(only = setOf("get_weather"))

            ToolLoop(model, tools, openTelemetry = telemetry.openTelemetry).run(ChatRequest("Weather in Rosario?"))

            val span = telemetry.named("execute_tool tester_get_weather")
            assertThat(span.attributes.get(stringKey("mcp.method.name"))).isEqualTo("tools/call")
            assertThat(span.attributes.get(stringKey("jsonrpc.request.id"))).isEqualTo("2")
            assertThat(telemetry.spans.map { it.name }).doesNotContain("tools/call get_weather")
            assertThat(sentBody().path("params._meta.traceparent")?.asString()).contains(span.spanId)
        }
    }

    @Nested
    inner class `a server before 2026-07-28` {
        @Test
        fun `opens its session in a span of its own, and says it and its version on the requests after`() {
            httpClient.answer(legacy("everything-modern-rejected.json"), status = 400)
            httpClient.answer(legacy("everything-initialize.txt"), contentType = EVENT_STREAM, headers = SESSION)
            httpClient.answer("", status = 202, contentType = "")
            httpClient.answer(legacy("everything-call-echo.txt"), contentType = EVENT_STREAM, headers = SESSION)

            client.callTool("echo", Json.obj("message" to "hola"))

            val call = telemetry.named("tools/call echo")
            assertThat(telemetry.spans.map { it.name }).contains("initialize")
            assertThat(call.attributes.get(stringKey("mcp.protocol.version"))).isEqualTo("2025-11-25")
            assertThat(call.attributes.get(stringKey("mcp.session.id"))).isEqualTo(SESSION.values.single())
        }
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/mcp/$name")?.readText() ?: error("Missing fixture $name")

    private fun legacy(name: String) = fixture("legacy/$name")

    private val telemetry = TestTelemetry()
    private val httpClient = FakeHttpClient()
    private val client = McpClient.http(
        "tester",
        "http://127.0.0.1:3001/mcp",
        httpClient = httpClient,
        openTelemetry = telemetry.openTelemetry,
    )

    private companion object {
        const val EVENT_STREAM = "text/event-stream"
        val SESSION = mapOf("mcp-session-id" to "7b353614-9172-4db6-b8be-92d79947f039")
    }
}
