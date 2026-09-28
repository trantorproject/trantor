package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.HttpStreamResponse
import dev.botta.trantor.web.client.sse.sseEvents
import java.util.Base64
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
): McpClient {
    private val ids = AtomicLong()
    private val lock = Any()

    /** What the server speaks, once a request told; until then, null. */
    @Volatile
    private var revision: Revision? = null

    override fun listTools(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null

        do {
            val params = cursor?.let { Json.obj("cursor" to it) } ?: JsonObject()
            val result = send(Request("tools/list", params))

            result["tools"]?.asArray().orEmpty().mapNotNullTo(tools) { it.asObject()?.let(McpMessages::toolOf) }
            cursor = result["nextCursor"]?.asString()
        } while (cursor != null)

        return tools
    }

    override fun callTool(name: String, arguments: JsonObject) =
        McpMessages.toolResultOf(send(Request("tools/call", Json.obj("name" to name, "arguments" to arguments), name)))

    /** Ends the session of a server of before, if it gave one. The server may not allow it, and that is fine. */
    override fun close() {
        val earlier = revision as? Revision.Earlier ?: return
        if (earlier.session == null) return

        runCatching { httpClient.delete(HttpRequest(url, headers = headersOf(earlier))) }
    }

    private fun send(request: Request): JsonObject = when (val revision = revision) {
        Revision.Current -> sendCurrent(request)
        is Revision.Earlier -> sendEarlier(request, revision)
        // The first request tells, one at a time, so that calls in parallel open a single session
        null -> synchronized(lock) { if (this.revision == null) sendFirst(request) else null } ?: send(request)
    }

    private fun sendFirst(request: Request): JsonObject {
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
    private fun speaksAnEarlierRevision(error: McpError) = error.status in 400..499 && error.code !in CURRENT_ERRORS

    private fun sendCurrent(request: Request): JsonObject {
        val body = McpMessages.request(ids.incrementAndGet(), request.method, request.params)

        return post(body, currentHeaders(request)) { answer(it, request.what) }
    }

    /**
     * A request to a server of before, in the session of [earlier]. A server that lost the session, like one that
     * restarted, turns the request down before it runs it, so a new session opens and the request goes once more.
     * The spec says it answers 404, and the reference server answers 400: both open it again.
     */
    private fun sendEarlier(request: Request, earlier: Revision.Earlier, again: Boolean = true): JsonObject {
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

    private fun currentHeaders(request: Request) = buildMap {
        putAll(commonHeaders())
        // The body says the same: the spec mirrors it in headers so that a gateway can route without reading it
        put("MCP-Protocol-Version", McpMessages.PROTOCOL_VERSION)
        put("Mcp-Method", request.method)
        request.name?.let { put("Mcp-Name", headerValue(it)) }
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

    /**
     * A value as a header can carry it: as it is when it is plain ASCII, and in base64 otherwise, in the form the
     * spec defines. That covers a name with other letters, and one with a line break that would add a header.
     */
    private fun headerValue(value: String): String {
        val looksEncoded = value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX)
        val plain = value.all { it in ' '..'~' } && value.trim() == value && !looksEncoded

        if (plain) return value

        return BASE64_PREFIX + Base64.getEncoder().encodeToString(value.toByteArray()) + BASE64_SUFFIX
    }

    private class Request(val method: String, val params: JsonObject, val name: String? = null) {
        /** How the errors name it, like "tools/call deploy". */
        val what = listOfNotNull(method, name).joinToString(" ")
    }

    private sealed interface Revision {
        /** 2026-07-28: no handshake and no session. */
        data object Current: Revision

        /** A revision before it, with the version the handshake agreed and the session it opened, if any. */
        class Earlier(val version: String, val session: String?): Revision
    }

    private companion object {
        const val BASE64_PREFIX = "=?base64?"
        const val BASE64_SUFFIX = "?="
        const val SESSION_HEADER = "Mcp-Session-Id"

        /** HeaderMismatch, MissingRequiredClientCapability and UnsupportedProtocolVersion, new in 2026-07-28. */
        val CURRENT_ERRORS = setOf(-32020, -32021, -32022)

        val LOST_SESSION = setOf(400, 404)
    }
}
