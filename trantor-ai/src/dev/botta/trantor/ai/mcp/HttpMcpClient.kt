package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
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
): BaseMcpClient() {
    private val ids = AtomicLong()
    private val lock = Any()

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
    override fun callTool(name: String, arguments: JsonObject): McpToolResult {
        return try {
            super.callTool(name, arguments)
        } catch (e: McpError) {
            if (e.code != McpMessages.HEADER_MISMATCH) throw e

            listTools()
            super.callTool(name, arguments)
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

        val earlier = handshake()
        revision = earlier
        return sendEarlier(request, earlier)
    }

    /**
     * Whether the server turned down a request of 2026-07-28 because it speaks a revision of before: a 4xx whose
     * body is not one of the errors of the new revision, which a server of before does not know.
     */
    private fun speaksAnEarlierRevision(error: McpError) =
        error.status in 400..499 && error.code !in McpMessages.CURRENT_ERRORS

    private fun sendCurrent(request: McpRequest): JsonObject {
        val body = McpMessages.request(ids.incrementAndGet(), request.method, request.params)

        return post(body, currentHeaders(request)) { answer(it, request.what) }
    }

    /**
     * A request to a server of before, in the session of [earlier]. A server that lost the session, like one that
     * restarted, turns the request down before it runs it, so a new session opens and the request goes once more.
     * The spec says it answers 404, and the reference server answers 400: both open it again.
     */
    private fun sendEarlier(request: McpRequest, earlier: Revision.Earlier, again: Boolean = true): JsonObject {
        val body = McpMessages.earlierRequest(ids.incrementAndGet(), request.method, request.params)

        val result = post(body, headersOf(earlier)) {
            if (again && earlier.session != null && it.status in LOST_SESSION) null else answer(it, request.what)
        }

        return result ?: sendEarlier(request, reopen(earlier), again = false)
    }

    private fun reopen(lost: Revision.Earlier) = synchronized(lock) {
        // Another request may have opened it again already
        (revision as? Revision.Earlier)?.takeIf { it !== lost } ?: handshake().also { revision = it }
    }

    /** `initialize` and `notifications/initialized`, the handshake of the revisions before 2026-07-28. */
    private fun handshake(): Revision.Earlier {
        val earlier = post(McpMessages.initialize(ids.incrementAndGet()), commonHeaders()) {
            val result = answer(it, "initialize")
            val session = it.headers.entries.firstOrNull { header -> header.key.equals(SESSION_HEADER, true) }?.value
            val version = result["protocolVersion"]?.asString() ?: McpMessages.EARLIER_PROTOCOL_VERSION

            Revision.Earlier(version, session)
        }

        post(McpMessages.notification("notifications/initialized"), headersOf(earlier)) {
            if (it.status !in 200..299) {
                throw McpError(
                    "The MCP server turned down notifications/initialized with ${it.status}: ${it.body()}",
                    status = it.status,
                )
            }
        }

        return earlier
    }

    private fun <T> post(body: JsonObject, headers: Map<String, String>, read: (HttpStreamResponse) -> T): T =
        httpClient.stream(HttpMethods.Post, HttpRequest(url, body.toString(), headers)).use(read)

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
        put("MCP-Protocol-Version", McpMessages.PROTOCOL_VERSION)
        put("Mcp-Method", request.method)
        request.name?.let { put("Mcp-Name", McpParamHeaders.encode(it)) }
        if (request.method == "tools/call") {
            val declarations = request.name?.let { this@HttpMcpClient.declarations?.get(it) }.orEmpty()
            putAll(McpParamHeaders.of(declarations, request.params["arguments"]?.asObject() ?: JsonObject()))
        }
    }

    private fun headersOf(earlier: Revision.Earlier) = buildMap {
        putAll(commonHeaders())
        put("MCP-Protocol-Version", earlier.version)
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
        const val SESSION_HEADER = "Mcp-Session-Id"

        val LOST_SESSION = setOf(400, 404)

        private val logger = getLogger<HttpMcpClient>()
    }
}
