package dev.botta.trantor.mcp.server

import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.mcp.McpProtocol
import dev.botta.trantor.primitives.TrantorBuildInfo
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

/**
 * What the traces and the metrics show of the requests an MCP endpoint answers, following the conventions of
 * OpenTelemetry for MCP on the side of the server, in Development like the ones of GenAI:
 *
 * - Each request or notification is a `SERVER` span named after its method, and after its tool on `tools/call`
 *   (`tools/call place_order`), current while it runs, so that what the tool does hangs from it.
 * - Its parent is the context the client sent in the `_meta` of the request, and it has a link to the span of the
 *   HTTP request, which is another trace when the client did not send that one: an MCP request and the HTTP request
 *   that carries it are not the same thing. Without a context in `_meta`, it hangs from the HTTP request.
 * - `mcp.server.operation.duration` measures each one.
 *
 * The name of a tool goes in the name of the span and in the metric only when it is one of the endpoint: anybody
 * can send any name, and one of each would make a name and a series of the metric per made-up tool.
 */
internal class McpServerTelemetry(openTelemetry: OpenTelemetry) {
    private val tracer = openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version)
    private val propagator = openTelemetry.propagators.textMapPropagator
    private val duration = openTelemetry.getMeter(INSTRUMENTATION)
        .histogramBuilder("mcp.server.operation.duration")
        .setUnit("s")
        .setDescription(
            "MCP request or notification duration as observed on the receiver from the time it was received until " +
                "the result or ack is sent.",
        )
        .setExplicitBucketBoundariesAdvice(BUCKETS)
        .build()

    /**
     * Runs [block], which answers the request of [method], in its span.
     *
     * @param tool the name of the tool a `tools/call` asks for, and [known] whether the endpoint has one of that name.
     */
    fun operation(
        request: McpHttpRequest,
        method: String,
        id: JsonValue?,
        meta: JsonObject?,
        tool: String?,
        known: Boolean,
        block: (McpOperation) -> McpHttpResponse,
    ): McpHttpResponse {
        val started = System.nanoTime()
        val ambient = Context.current()
        val parent = meta?.let { propagator.extract(ambient, it, MetaGetter) } ?: ambient
        val metric = Attributes.builder()
            .put(METHOD, method)
            .put(TRANSPORT, "tcp")
            .put(PROTOCOL_NAME, "http")
        request.httpVersion?.let { metric.put(PROTOCOL_VERSION, it) }
        if (method == TOOLS_CALL) metric.put(OPERATION, "execute_tool")
        if (tool != null && known) metric.put(TOOL_NAME, tool)

        val span = tracer.spanBuilder(if (tool != null && known) "$method $tool" else method)
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .setAllAttributes(metric.build())
            .apply {
                tool?.let { setAttribute(TOOL_NAME, it) }
                id?.takeUnless { it.isNull }?.let { setAttribute(REQUEST_ID, it.asString() ?: it.toString()) }
                request.clientAddress?.let { setAttribute(CLIENT_ADDRESS, it) }
                request.clientPort?.let { setAttribute(CLIENT_PORT, it.toLong()) }
                // The HTTP request, when it is not already the parent
                val http = Span.fromContext(ambient).spanContext
                if (http.isValid && http != Span.fromContext(parent).spanContext) addLink(http)
            }
            .startSpan()
        val operation = McpOperation(span, metric)

        try {
            return parent.with(span).makeCurrent().use { block(operation) }
        } finally {
            span.end()
            duration.record((System.nanoTime() - started) / 1e9, operation.metric.build())
        }
    }

    private object MetaGetter: TextMapGetter<JsonObject> {
        override fun keys(carrier: JsonObject) = carrier.keys

        override fun get(carrier: JsonObject?, key: String) = carrier?.get(key)?.takeIf { it.isString }?.asString()
    }

    private companion object {
        const val INSTRUMENTATION = "dev.botta.trantor.mcp.server"
        const val TOOLS_CALL = "tools/call"

        /** The ones the conventions give for this metric. */
        val BUCKETS = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0)

        val METHOD = stringKey("mcp.method.name")
        val REQUEST_ID = stringKey("jsonrpc.request.id")
        val TRANSPORT = stringKey("network.transport")
        val PROTOCOL_NAME = stringKey("network.protocol.name")
        val PROTOCOL_VERSION = stringKey("network.protocol.version")
        val OPERATION = stringKey("gen_ai.operation.name")
        val TOOL_NAME = stringKey("gen_ai.tool.name")
        val CLIENT_ADDRESS = stringKey("client.address")
        val CLIENT_PORT = longKey("client.port")
    }
}

/** What the endpoint tells the span and the metric of one request as it answers it. */
internal class McpOperation(private val span: Span, internal val metric: AttributesBuilder) {
    /** The revision of MCP the answer speaks. */
    fun speaks(version: String) {
        span.setAttribute(MCP_PROTOCOL_VERSION, version)
        metric.put(MCP_PROTOCOL_VERSION, version)
    }

    /**
     * Answered with the JSON-RPC error [code]. The ones that say the caller asked for what cannot be served are not
     * a failure of the server, as the conventions have it; any other fails the span with [message].
     */
    fun answeredError(code: Int, message: String, exception: Throwable? = null) {
        put(STATUS_CODE, code.toString())
        if (code in CALLER_MISTAKES) return

        put(ERROR_TYPE, code.toString())
        span.setStatus(StatusCode.ERROR, message)
        exception?.let { span.recordException(it) }
    }

    /** The tool ran and answered with `isError`: a result, not a failure of the server. */
    fun toolFailed() = put(ERROR_TYPE, "tool_error")

    private fun put(key: AttributeKey<String>, value: String) {
        span.setAttribute(key, value)
        metric.put(key, value)
    }

    private companion object {
        val MCP_PROTOCOL_VERSION = stringKey("mcp.protocol.version")
        val STATUS_CODE = stringKey("rpc.response.status_code")
        val ERROR_TYPE = stringKey("error.type")

        val CALLER_MISTAKES = setOf(
            McpProtocol.Errors.PARSE_ERROR,
            McpProtocol.Errors.INVALID_REQUEST,
            McpProtocol.Errors.METHOD_NOT_FOUND,
            McpProtocol.Errors.INVALID_PARAMS,
        )
    }
}
