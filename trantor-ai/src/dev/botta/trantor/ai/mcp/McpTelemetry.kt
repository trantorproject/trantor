package dev.botta.trantor.ai.mcp

import dev.botta.trantor.primitives.TrantorBuildInfo
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.propagation.TextMapSetter
import java.net.URI

/**
 * What the traces show of the requests to an MCP server, following the conventions of OpenTelemetry for MCP, in
 * Development like the ones of GenAI:
 *
 * - Each request goes in a client span named after its method, and after its tool on `tools/call`
 *   (`tools/call get_weather`), with `mcp.method.name`, `jsonrpc.request.id`, `mcp.protocol.version`,
 *   `mcp.session.id`, `network.transport` and the address of the server.
 * - A tool an agent calls already has its `execute_tool` span, and the conventions ask not to make a second one: the
 *   MCP attributes go on that one. [McpTool] says so through the context.
 * - The context of the trace goes in the `_meta` of the request (`traceparent`, `tracestate`, `baggage`), for a
 *   server that traces to continue it.
 */
internal class McpTelemetry(openTelemetry: OpenTelemetry, private val transport: String, url: String? = null) {
    private val tracer = openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version)
    private val propagator = openTelemetry.propagators.textMapPropagator
    private val address = url?.let { runCatching { URI(it) }.getOrNull() }

    /** Runs [block], which sends [request], in its span, or in the one of the tool that makes it. */
    fun <T> request(request: McpRequest, block: () -> T): T {
        if (request.method == TOOLS_CALL && Context.current().get(CALLED_BY_TOOL) == true) {
            describe(Span.current(), request)
            return block()
        }

        val name = listOfNotNull(request.method, request.name).joinToString(" ")
        val span = tracer.spanBuilder(name).setSpanKind(SpanKind.CLIENT).startSpan()
        describe(span, request)

        try {
            return span.makeCurrent().use { block() }
        } catch (e: Throwable) {
            failed(span, e)
            throw e
        } finally {
            span.end()
        }
    }

    /** What is known once the request goes: its id, the version it speaks and the session of a server of before. */
    fun sent(id: Long, version: String, session: String? = null) {
        val span = Span.current()
        span.setAttribute(REQUEST_ID, id.toString())
        span.setAttribute(PROTOCOL_VERSION, version)
        session?.let { span.setAttribute(SESSION_ID, it) }
    }

    /**
     * A tool that ran and failed, on the span of a call made directly. On the span of a tool of an agent, the failure
     * is written by the one who made it.
     */
    fun toolFailed() {
        if (Context.current().get(CALLED_BY_TOOL) == true) return

        Span.current().setAttribute(ERROR_TYPE, "tool_error")
    }

    /** The context of the trace as it goes in `_meta`. */
    fun context(): Map<String, String> {
        val carrier = linkedMapOf<String, String>()
        propagator.inject(Context.current(), carrier, SETTER)
        return carrier
    }

    private fun describe(span: Span, request: McpRequest) {
        span.setAttribute(METHOD, request.method)
        span.setAttribute(TRANSPORT, transport)
        if (request.method == TOOLS_CALL) {
            span.setAttribute(OPERATION, "execute_tool")
            request.name?.let { span.setAttribute(TOOL_NAME, it) }
        }
        address?.host?.let { span.setAttribute(SERVER_ADDRESS, it) }
        address?.port?.takeIf { it > 0 }?.let { span.setAttribute(SERVER_PORT, it.toLong()) }
    }

    private fun failed(span: Span, error: Throwable) {
        val code = (error as? McpError)?.code

        span.setStatus(StatusCode.ERROR, error.message.orEmpty())
        span.setAttribute(ERROR_TYPE, code?.toString() ?: error.javaClass.name)
        code?.let { span.setAttribute(STATUS_CODE, it.toString()) }
        span.recordException(error)
    }

    companion object {
        private const val INSTRUMENTATION = "dev.botta.trantor.ai"
        private const val TOOLS_CALL = "tools/call"
        private const val METHOD = "mcp.method.name"
        private const val REQUEST_ID = "jsonrpc.request.id"
        private const val PROTOCOL_VERSION = "mcp.protocol.version"
        private const val SESSION_ID = "mcp.session.id"
        private const val TRANSPORT = "network.transport"
        private const val SERVER_ADDRESS = "server.address"
        private const val SERVER_PORT = "server.port"
        private const val OPERATION = "gen_ai.operation.name"
        private const val TOOL_NAME = "gen_ai.tool.name"
        private const val ERROR_TYPE = "error.type"
        private const val STATUS_CODE = "rpc.response.status_code"

        private val CALLED_BY_TOOL = ContextKey.named<Boolean>("trantor.mcp.called-by-tool")

        private val SETTER = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
            carrier?.put(key, value)
        }

        /** Runs [block] saying that a tool traces the call it makes, so that the call adds to its span. */
        fun <T> calledByTool(block: () -> T): T = Context.current().with(CALLED_BY_TOOL, true).makeCurrent().use {
            block()
        }
    }
}
