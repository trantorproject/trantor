package dev.botta.trantor.mcp.server

/** A request to an MCP endpoint over HTTP, as [McpEndpoint] reads it: no matter which server received it. */
class McpHttpRequest(
    /** The HTTP method, like POST. */
    val method: String,
    val body: String,
    private val headers: Map<String, String> = emptyMap(),
) {
    /** A header by its name, which HTTP compares ignoring case. */
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** What [McpEndpoint] answers: a status, and a body of JSON when there is one. */
data class McpHttpResponse(val status: Int, val body: String? = null, val headers: Map<String, String> = emptyMap())
