package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.throwIfCancelled
import dev.botta.trantor.web.client.StreamOptions
import java.io.InterruptedIOException
import io.opentelemetry.api.OpenTelemetry
import kotlin.time.Duration
import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.HttpStreamResponse
import dev.botta.trantor.web.client.sse.sseEvents
import dev.botta.trantor.primitives.logging.getLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * An MCP client over Streamable HTTP. Every request is a POST of its own, and the server answers it with its JSON,
 * or with an event stream that carries notifications about it and then the answer.
 *
 * It speaks 2026-07-28, and also the revisions before it, which most servers still speak. Its first request tells
 * which one the server speaks, as the spec says: a server of before turns down a request of the new revision with an
 * error it does not know, and then this client opens a session with the handshake of before, `initialize`, and keeps
 * using it. Which one the server speaks is kept for the life of the client.
 */
internal class HttpMcpClient(
    override val name: String,
    private val url: String,
    private val headers: Map<String, String>,
    private val httpClient: HttpClient,
    private val requestTimeout: Duration = McpClient.DEFAULT_REQUEST_TIMEOUT,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): BaseMcpClient() {
    private val ids = AtomicLong()
    private val lock = Any()

    override val telemetry = McpTelemetry(openTelemetry, "tcp", url)

    /**
     * Which arguments of each tool of a server of 2026-07-28 go in `Mcp-Param-*` headers, from the last listing;
     * null until one.
     */
    @Volatile
    private var declarations: Map<String, List<McpParamHeaders.Declaration>>? = null

    /** What the server speaks, once a request told; until then, null. */
    @Volatile
    private var revision: Revision? = null

    /**
     * Calls the tool with the headers its schema asks for, as the last listing said. When the server says they do
     * not match, the tool was not listed or changed since, so it is listed again and called once more, as the spec
     * says: the server turned the call down before running it. Listing before every call would cost a request each
     * time for what is rare.
     */
    override fun callTool(name: String, arguments: JsonObject, options: CallOptions): McpToolResult {
        return try {
            super.callTool(name, arguments, options)
        } catch (e: McpError) {
            if (e.code != McpProtocol.Errors.HEADER_MISMATCH) throw e

            listTools(options)
            super.callTool(name, arguments, options)
        }
    }

    /**
     * A server of 2026-07-28 may mark arguments to go in headers too, and a tool whose marks break the rules of the
     * spec is left out, as the spec asks of a client over HTTP, with a warning that says why.
     */
    override fun listed(tools: List<McpToolDefinition>): List<McpToolDefinition> {
        if (revision != Revision.Current) {
            declarations = emptyMap()
            return tools
        }

        val valid = mutableListOf<McpToolDefinition>()
        val found = mutableMapOf<String, List<McpParamHeaders.Declaration>>()

        for (tool in tools) {
            when (val scan = McpParamHeaders.scan(tool.inputSchema)) {
                is McpParamHeaders.Scan.Valid -> {
                    valid.add(tool)
                    found[tool.name] = scan.declarations
                }
                is McpParamHeaders.Scan.Invalid ->
                    logger.warn("Leaving out the tool ${tool.name} of the MCP server $name: ${scan.reason}")
            }
        }

        declarations = found
        return valid
    }

    /** Ends the session of a server of before, if it gave one. The server may not allow it, and that is fine. */
    override fun close() {
        val earlier = revision as? Revision.Earlier ?: return
        if (earlier.session == null) return

        runCatching { httpClient.delete(HttpRequest(url, headers = headersOf(earlier))) }
    }

    override fun send(request: McpRequest): JsonObject = when (val revision = revision) {
        Revision.Current -> sendCurrent(request)
        is Revision.Earlier -> sendEarlier(request, revision)
        // The first request tells, one at a time, so that calls in parallel open a single session
        null -> synchronized(lock) { if (this.revision == null) sendFirst(request) else null } ?: send(request)
    }

    private fun sendFirst(request: McpRequest): JsonObject {
        try {
            return sendCurrent(request).also { revision = Revision.Current }
        } catch (e: McpError) {
            if (!speaksAnEarlierRevision(e)) {
                // An error of the new revision says the server speaks it; a 5xx does not say anything yet
                if (e.status in 400..499) revision = Revision.Current
                throw e
            }
        }

        val earlier = handshake(request.options)
        revision = earlier
        return sendEarlier(request, earlier)
    }

    /**
     * Whether the server turned down a request of 2026-07-28 because it speaks a revision of before: a 400 whose
     * body is not one of the errors of the new revision, which a server of before does not know. Only a 400, as the
     * spec says: a 401 or a 403 asks for credentials, whatever the revision.
     */
    private fun speaksAnEarlierRevision(error: McpError) =
        error.status == 400 && error.code !in McpMessages.CURRENT_ERRORS

    private fun sendCurrent(request: McpRequest): JsonObject {
        val id = ids.incrementAndGet()
        val body = McpMessages.request(id, request.method, request.params, telemetry.context())
        telemetry.sent(id, McpProtocol.VERSION)

        return post(body, currentHeaders(request), request.options, request.what) { answer(it, request.what) }
    }

    /**
     * A request to a server of before, in the session of [earlier]. A server that lost the session, like one that
     * restarted, turns the request down before it runs it, so a new session opens and the request goes once more.
     * The spec says it answers 404, and the reference server answers 400: both open it again.
     */
    private fun sendEarlier(request: McpRequest, earlier: Revision.Earlier, again: Boolean = true): JsonObject {
        val id = ids.incrementAndGet()
        val body = McpMessages.earlierRequest(id, request.method, request.params, telemetry.context())
        telemetry.sent(id, earlier.version, earlier.session)

        val result = try {
            post(body, headersOf(earlier), request.options, request.what) {
                if (again && earlier.session != null && it.status in LOST_SESSION) null else answer(it, request.what)
            }
        } catch (e: CancelledError) {
            // Before 2026-07-28 a stream that closes is not a cancellation: the server is told
            val cancelled = McpMessages.cancelled(id)
            runCatching { post(cancelled, headersOf(earlier), CallOptions(), "notifications/cancelled") {} }
            throw e
        }

        return result ?: sendEarlier(request, reopen(earlier, request.options), again = false)
    }

    private fun reopen(lost: Revision.Earlier, options: CallOptions) = synchronized(lock) {
        // Another request may have opened it again already
        (revision as? Revision.Earlier)?.takeIf { it !== lost } ?: handshake(options).also { revision = it }
    }

    /** `initialize` and `notifications/initialized`, the handshake of the revisions before 2026-07-28. */
    private fun handshake(options: CallOptions) = telemetry.request(McpRequest("initialize", JsonObject())) {
        val id = ids.incrementAndGet()
        telemetry.sent(id, McpProtocol.EARLIER_VERSION)

        val earlier = post(McpMessages.initialize(id), commonHeaders(), options, "initialize") {
            val result = answer(it, "initialize")
            val session = it.headers.entries.firstOrNull { header -> header.key.equals(SESSION_HEADER, true) }?.value
            val version = result["protocolVersion"]?.asString() ?: McpProtocol.EARLIER_VERSION

            Revision.Earlier(version, session)
        }

        post(McpMessages.notification("notifications/initialized"), headersOf(earlier), options, "initialize") {
            if (it.status !in 200..299) {
                throw McpError(
                    "The MCP server turned down notifications/initialized with ${it.status}: ${it.body()}",
                    status = it.status,
                )
            }
        }

        earlier
    }

    /**
     * Posts [body] and reads its answer with [read], within the shorter of the timeout of the run and the one of the
     * client, which is the time the whole stream may take. Cancelling the run cancels the stream, and what was read
     * of it is not taken for an answer. What fails on the way to the server is an [McpError] that names it.
     */
    private fun <T> post(
        body: JsonObject,
        headers: Map<String, String>,
        options: CallOptions,
        what: String,
        read: (HttpStreamResponse) -> T,
    ): T {
        options.cancellation?.throwIfCancelled()

        val timeout = listOfNotNull(options.timeout, requestTimeout).min()
        val cancellation = options.cancellation
        val streamOptions = StreamOptions(
            totalTimeout = timeout.inWholeMilliseconds.toInt(),
            cancellation = cancellation,
        )
        val httpRequest = HttpRequest(url, body.toString(), headers)

        val response = try {
            httpClient.stream(HttpMethods.Post, httpRequest, streamOptions)
        } catch (e: Exception) {
            // A call cut while it waits for the answer fails on its way, which says nothing of the server
            cancellation?.throwIfCancelled()
            throw unreachable(e, what, timeout)
        }

        return response.use {
            try {
                read(it).also { cancellation?.throwIfCancelled() }
            } catch (e: CancelledError) {
                throw e
            } catch (e: McpError) {
                // A cancelled stream ends as if the server stopped writing, which says nothing of the server
                cancellation?.throwIfCancelled()
                throw e
            } catch (e: Exception) {
                cancellation?.throwIfCancelled()
                throw unreachable(e, what, timeout)
            }
        }
    }

    private fun unreachable(error: Throwable, what: String, timeout: Duration): McpError {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is InterruptedIOException }) {
            return McpError("The MCP server $name did not answer $what in $timeout", cause = error)
        }

        return McpError("Could not reach the MCP server $name for $what: ${error.message}", cause = error)
    }

    private fun answer(response: HttpStreamResponse, what: String): JsonObject {
        if (response.status != 200) {
            val body = response.body()
            val error = runCatching { Json.parse(body).asObject()?.get("error")?.asObject() }.getOrNull()
                ?: throw McpError(
                    "The MCP server answered $what with ${response.status}: $body",
                    status = response.status,
                )

            throw McpMessages.errorOf(error, what, response.status)
        }

        val answer = if (response.contentType?.startsWith("text/event-stream") == true) {
            // Only the answer to this request comes on its stream, after the notifications about it, like its
            // progress. A server of before may start the stream with an event without data, to be able to resume it.
            response.sseEvents()
                .mapNotNull { runCatching { Json.parse(it.data).asObject() }.getOrNull() }
                .firstOrNull { it.containsKey("result") || it.containsKey("error") }
                ?: throw McpError("The MCP server closed the stream of $what without answering it")
        } else {
            Json.parse(response.body()).asObject()
                ?: throw McpError("The MCP server answered $what with no JSON object")
        }

        return McpMessages.resultOf(answer, what)
    }

    private fun currentHeaders(request: McpRequest) = buildMap {
        putAll(commonHeaders())
        // The body says the same: the spec mirrors it in headers so that a gateway can route without reading it
        put(McpProtocol.Headers.PROTOCOL_VERSION, McpProtocol.VERSION)
        put(McpProtocol.Headers.METHOD, request.method)
        request.name?.let { put(McpProtocol.Headers.NAME, McpProtocol.encodeHeaderValue(it)) }
        if (request.method == "tools/call") {
            val declarations = request.name?.let { this@HttpMcpClient.declarations?.get(it) }.orEmpty()
            putAll(McpParamHeaders.of(declarations, request.params["arguments"]?.asObject() ?: JsonObject()))
        }
    }

    private fun headersOf(earlier: Revision.Earlier) = buildMap {
        putAll(commonHeaders())
        put(McpProtocol.Headers.PROTOCOL_VERSION, earlier.version)
        earlier.session?.let { put(SESSION_HEADER, it) }
    }

    private fun commonHeaders() = buildMap {
        putAll(headers)
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
    }

    private sealed interface Revision {
        /** 2026-07-28: no handshake and no session. */
        data object Current: Revision

        /** A revision before it, with the version the handshake agreed and the session it opened, if any. */
        class Earlier(val version: String, val session: String?): Revision
    }

    private companion object {
        const val SESSION_HEADER = McpProtocol.Headers.SESSION

        val LOST_SESSION = setOf(400, 404)

        private val logger = getLogger<HttpMcpClient>()
    }
}
