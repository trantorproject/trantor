@file:Suppress("ClassName")

package dev.botta.trantor.mcp.server

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.mcp.McpProtocol
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.mcp.server.testing.TestTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** What the traces show of the requests an MCP endpoint answers, as the conventions of OpenTelemetry for MCP ask. */
class McpEndpointTracingTest {
    @Nested
    inner class `a call to a tool` {
        @Test
        fun `is a server span named after the method and the tool, with what the conventions ask`() {
            call("echo", Json.obj("text" to "hola"), id = 7)

            val span = telemetry.named("tools/call echo")
            assertThat(span.kind).isEqualTo(SpanKind.SERVER)
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
            assertThat(span.attributes.asMap().mapKeys { it.key.key }).isEqualTo(
                mapOf(
                    "mcp.method.name" to "tools/call",
                    "gen_ai.tool.name" to "echo",
                    "gen_ai.operation.name" to "execute_tool",
                    "jsonrpc.request.id" to "7",
                    "mcp.protocol.version" to "2026-07-28",
                    "network.transport" to "tcp",
                    "network.protocol.name" to "http",
                    "network.protocol.version" to "1.1",
                    "client.address" to "10.0.0.7",
                    "client.port" to 51234L,
                ),
            )
        }

        @Test
        fun `goes on from the trace the client sent in _meta, with a link to the span of the HTTP request`() {
            val client = telemetry.tracer.spanBuilder("tools/call echo").startSpan().also { it.end() }.spanContext

            val http = inHttpRequest { call("echo", Json.obj("text" to "hola"), meta = traceOf(client)) }

            val span = serverSpan("tools/call echo")
            assertThat(span.traceId).isEqualTo(client.traceId)
            assertThat(span.parentSpanId).isEqualTo(client.spanId)
            assertThat(span.links.map { it.spanContext }).containsExactly(http)
        }

        @Test
        fun `without a trace in _meta, hangs from the span of the HTTP request, without links`() {
            val http = inHttpRequest { call("echo", Json.obj("text" to "hola")) }

            val span = serverSpan("tools/call echo")
            assertThat(span.parentSpanId).isEqualTo(http.spanId)
            assertThat(span.links).isEmpty()
        }

        @Test
        fun `is current while the tool runs, so what the tool does hangs from it`() {
            call("trace", Json.obj())

            val server = serverSpan("tools/call trace")
            assertThat(telemetry.named("load the order").parentSpanId).isEqualTo(server.spanId)
        }

        @Test
        fun `gives the tool the baggage the client sent`() {
            call("trace", Json.obj(), meta = Json.obj("baggage" to "tenant=crafty"))

            assertThat(tracing.baggage).isEqualTo("crafty")
        }
    }

    @Nested
    inner class `a request of another method` {
        @Test
        fun `is named after its method alone, and is no execution of a tool`() {
            post("tools/list")

            val span = serverSpan("tools/list")
            assertThat(span.attributes[stringKey("mcp.method.name")]).isEqualTo("tools/list")
            assertThat(span.attributes[stringKey("gen_ai.operation.name")]).isNull()
            assertThat(span.attributes[stringKey("gen_ai.tool.name")]).isNull()
        }

        @Test
        fun `that is a notification has no request id`() {
            handle(Json.obj("jsonrpc" to "2.0", "method" to "notifications/initialized"), version = "2025-11-25")

            val span = serverSpan("notifications/initialized")
            assertThat(span.attributes[stringKey("jsonrpc.request.id")]).isNull()
        }

        @Test
        fun `of a client of before says the version agreed in the handshake`() {
            handle(request("initialize", Json.obj("protocolVersion" to "2025-06-18")), version = null)

            val span = serverSpan("initialize")
            assertThat(span.attributes[stringKey("mcp.protocol.version")]).isEqualTo("2025-06-18")
        }

        @Test
        fun `of a client of before says the version of its header`() {
            handle(request("tools/list"), version = "2025-11-25")

            val span = serverSpan("tools/list")
            assertThat(span.attributes[stringKey("mcp.protocol.version")]).isEqualTo("2025-11-25")
        }

        @Test
        fun `that is not JSON-RPC is no MCP span, since it has no method to name it`() {
            endpoint.handle(McpHttpRequest("POST", "not json"))

            assertThat(telemetry.spans).isEmpty()
        }
    }

    @Nested
    inner class `what failed` {
        @Test
        fun `a tool that answered with an error is a tool error, and the span is not failed`() {
            call("refund", Json.obj("amount" to 500))

            val span = serverSpan("tools/call refund")
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("tool_error")
            assertThat(span.attributes[stringKey("rpc.response.status_code")]).isNull()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `a tool that does not exist is the caller's mistake, and its name is not the name of the span`() {
            call("nope", Json.obj())

            val span = serverSpan("tools/call")
            assertThat(span.attributes[stringKey("gen_ai.tool.name")]).isEqualTo("nope")
            assertThat(span.attributes[stringKey("rpc.response.status_code")]).isEqualTo("-32602")
            assertThat(span.attributes[stringKey("error.type")]).isNull()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `asking without saying who one is, where it is required, is the caller's mistake`() {
            val guarded = McpEndpoint(
                "store",
                "1.0.0",
                listOf(echo),
                requireAuthentication = true,
                openTelemetry = telemetry.openTelemetry,
            )

            val body = request("tools/list", meta = meta()).toString()

            guarded.handle(McpHttpRequest("POST", body, headers("tools/list")))

            val span = serverSpan("tools/list")
            assertThat(span.attributes[stringKey("rpc.response.status_code")]).isEqualTo("-32600")
            assertThat(span.attributes[stringKey("error.type")]).isNull()
        }

        @Test
        fun `a version it does not speak fails the span with the code and the message of the error`() {
            val body = request("tools/list", meta = meta(version = "2025-01-01"))
            handle(body, headers = headers("tools/list", version = "2025-01-01"))

            val span = serverSpan("tools/list")
            assertThat(span.attributes[stringKey("rpc.response.status_code")]).isEqualTo("-32022")
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("-32022")
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.status.description).isEqualTo("Unsupported protocol version: 2025-01-01")
        }

        @Test
        fun `what nobody expected fails the span, and keeps the exception, which the answer does not say`() {
            call("explode", Json.obj())

            val span = serverSpan("tools/call explode")
            assertThat(span.attributes[stringKey("rpc.response.status_code")]).isEqualTo("-32603")
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("-32603")
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.status.description).isEqualTo("Internal error")
            assertThat(span.events.single().attributes[stringKey("exception.message")])
                .isEqualTo("the database password is hunter2")
        }
    }

    @Nested
    inner class `the duration of each operation` {
        @Test
        fun `is measured with what tells one from another, and with the buckets of the conventions`() {
            call("echo", Json.obj("text" to "hola"))
            call("refund", Json.obj("amount" to 500))

            val points = telemetry.metric("mcp.server.operation.duration").histogramData.points
            assertThat(points.map { it.attributes }).containsExactlyInAnyOrder(
                operation("echo"),
                operation("refund").toBuilder().put("error.type", "tool_error").build(),
            )
            assertThat(points.first().boundaries)
                .containsExactly(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0)
        }

        @Test
        fun `does not take the name of a tool that does not exist, which anybody can make up`() {
            call("made-up", Json.obj())

            val point = telemetry.metric("mcp.server.operation.duration").histogramData.points.single()
            assertThat(point.attributes[stringKey("gen_ai.tool.name")]).isNull()
            assertThat(point.attributes[stringKey("rpc.response.status_code")]).isEqualTo("-32602")
        }

        private fun operation(tool: String) = Attributes.builder()
            .put("mcp.method.name", "tools/call")
            .put("gen_ai.tool.name", tool)
            .put("gen_ai.operation.name", "execute_tool")
            .put("mcp.protocol.version", "2026-07-28")
            .put("network.transport", "tcp")
            .put("network.protocol.name", "http")
            .put("network.protocol.version", "1.1")
            .build()
    }

    private fun serverSpan(name: String): SpanData =
        telemetry.spans.single { it.name == name && it.kind == SpanKind.SERVER }

    /** Runs [block] inside a span that stands for the one of the HTTP request, as trantor-web makes it. */
    private fun inHttpRequest(block: () -> Unit): SpanContext {
        val http = telemetry.tracer.spanBuilder("POST /mcp").setSpanKind(SpanKind.SERVER).startSpan()
        http.makeCurrent().use { block() }
        http.end()
        return http.spanContext
    }

    private fun traceOf(span: SpanContext) = Json.obj("traceparent" to "00-${span.traceId}-${span.spanId}-01")

    private fun call(tool: String, arguments: JsonObject, id: Int = 1, meta: JsonObject = Json.obj()) {
        val body = request("tools/call", Json.obj("name" to tool, "arguments" to arguments), id, meta(meta))
        handle(body, headers = headers("tools/call", tool))
    }

    private fun post(method: String) = handle(request(method, meta = meta()), headers = headers(method))

    private fun request(method: String, params: JsonObject = Json.obj(), id: Int = 1, meta: JsonObject? = null) =
        Json.obj(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to JsonObject(params.toList()).apply { meta?.let { this["_meta"] = it } },
        )

    private fun meta(extra: JsonObject = Json.obj(), version: String = McpProtocol.VERSION) = Json.obj(
        McpProtocol.Meta.PROTOCOL_VERSION to version,
        McpProtocol.Meta.CLIENT_INFO to Json.obj("name" to "test", "version" to "1"),
        McpProtocol.Meta.CLIENT_CAPABILITIES to Json.obj(),
    ).apply { putAll(extra) }

    private fun headers(method: String, name: String? = null, version: String = McpProtocol.VERSION) = buildMap {
        put(McpProtocol.Headers.PROTOCOL_VERSION, version)
        put(McpProtocol.Headers.METHOD, method)
        name?.let { put(McpProtocol.Headers.NAME, it) }
    }

    private fun handle(body: JsonObject, headers: Map<String, String>) = endpoint.handle(
        McpHttpRequest(
            "POST",
            body.toString(),
            headers,
            clientAddress = "10.0.0.7",
            clientPort = 51234,
            httpVersion = "1.1",
        ),
    )

    /** A request of a client of before, which says its version, if at all, in the header alone. */
    private fun handle(body: JsonObject, version: String?) =
        handle(body, headers = version?.let { mapOf(McpProtocol.Headers.PROTOCOL_VERSION to it) }.orEmpty())

    private val telemetry = TestTelemetry()
    private val echo = EchoTool()
    private val tracing = TracingTool(telemetry)

    private val endpoint = McpEndpoint(
        "store",
        "1.0.0",
        listOf(echo, RefundTool(), ExplodingTool(), tracing),
        openTelemetry = telemetry.openTelemetry,
    )

    class EchoTool: Tool<EchoTool.Args>() {
        override val name = "echo"
        override val description = "Says back what it is given"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text(args.text)

        @Serializable
        data class Args(val text: String)
    }

    class RefundTool: Tool<RefundTool.Args>() {
        override val name = "refund"
        override val description = "Gives the money of an order back"

        override fun execute(args: Args, context: ToolContext): ToolResult {
            if (args.amount > 100) throw ToolError("Refunds over 100 need a manager")
            return ToolResult.text("Refunded")
        }

        @Serializable
        data class Args(val amount: Int)
    }

    class ExplodingTool: Tool<ExplodingTool.Args>() {
        override val name = "explode"
        override val description = "Fails as nobody expected"

        override fun execute(args: Args, context: ToolContext): ToolResult =
            throw IllegalStateException("the database password is hunter2")

        @Serializable
        class Args
    }

    /** Does what a use case does in its span, and keeps the baggage it was given. */
    class TracingTool(private val telemetry: TestTelemetry): Tool<TracingTool.Args>() {
        override val name = "trace"
        override val description = "Loads an order"
        var baggage: String? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            baggage = Baggage.current().getEntryValue("tenant")
            telemetry.tracer.spanBuilder("load the order").startSpan().end()
            return ToolResult.text("Loaded")
        }

        @Serializable
        class Args
    }
}
