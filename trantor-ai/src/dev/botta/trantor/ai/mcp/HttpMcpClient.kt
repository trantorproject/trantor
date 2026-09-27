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
 * An MCP client over Streamable HTTP, on the 2026-07-28 revision: every request is a POST of its own, and the server
 * answers it with its JSON, or with an event stream that carries notifications about it and then the answer.
 */
internal class HttpMcpClient(
    private val url: String,
    private val headers: Map<String, String>,
    private val httpClient: HttpClient,
): McpClient {
    private val ids = AtomicLong()

    override fun listTools(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null

        do {
            val params = cursor?.let { Json.obj("cursor" to it) } ?: JsonObject()
            val result = send("tools/list", params)

            result["tools"]?.asArray().orEmpty().mapNotNullTo(tools) { it.asObject()?.let(McpMessages::toolOf) }
            cursor = result["nextCursor"]?.asString()
        } while (cursor != null)

        return tools
    }

    override fun callTool(name: String, arguments: JsonObject) =
        McpMessages.toolResultOf(send("tools/call", Json.obj("name" to name, "arguments" to arguments), name))

    private fun send(method: String, params: JsonObject, name: String? = null): JsonObject {
        val id = ids.incrementAndGet()
        val body = McpMessages.request(id, method, params).toString()
        val what = listOfNotNull(method, name).joinToString(" ")

        return httpClient.stream(HttpMethods.Post, HttpRequest(url, body, headers(method, name))).use {
            answer(it, id, what)
        }
    }

    private fun answer(response: HttpStreamResponse, id: Long, what: String): JsonObject {
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
            // What comes before the answer are notifications about the request, like its progress
            response.sseEvents()
                .mapNotNull { runCatching { Json.parse(it.data).asObject() }.getOrNull() }
                .firstOrNull { it["id"]?.asLong() == id }
                ?: throw McpError("The MCP server closed the stream of $what without answering it")
        } else {
            Json.parse(response.body()).asObject()
                ?: throw McpError("The MCP server answered $what with no JSON object")
        }

        return McpMessages.resultOf(answer, what)
    }

    private fun headers(method: String, name: String?) = buildMap {
        putAll(headers)
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
        // The body says the same: the spec mirrors it in headers so that a gateway can route without reading it
        put("MCP-Protocol-Version", McpMessages.PROTOCOL_VERSION)
        put("Mcp-Method", method)
        name?.let { put("Mcp-Name", headerValue(it)) }
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

    private companion object {
        const val BASE64_PREFIX = "=?base64?"
        const val BASE64_SUFFIX = "?="
    }
}
