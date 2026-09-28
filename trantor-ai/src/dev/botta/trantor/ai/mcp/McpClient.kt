package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.providers.defaultHttpClient
import dev.botta.trantor.web.client.HttpClient

/**
 * A connection to one MCP server, to list the tools it offers and call them.
 *
 * It speaks the 2026-07-28 revision of the protocol, where every request stands on its own, and the revisions before
 * it, which most servers still speak: with one of those it opens a session with their handshake on the first request,
 * and opens it again if the server loses it. [close] ends that session. It declares no capabilities of its own, so a
 * server does not ask it for input, a completion of a model or its roots.
 *
 * ```kotlin
 * val github = McpClient.http("https://api.githubcopilot.com/mcp/", mapOf("Authorization" to "Bearer $token"))
 *
 * github.listTools()
 * github.callTool("search_issues", Json.obj("query" to "is:open label:bug"))
 * ```
 *
 * Listing is a call to the server, so it happens when it is asked for and not when the client is created.
 */
interface McpClient: AutoCloseable {
    /** Every tool of the server, following its pages until the last one. */
    fun listTools(): List<McpToolDefinition>

    /**
     * Calls a tool of the server with [arguments]. A tool that failed comes back with [McpToolResult.isError], as the
     * server tells it; what fails is the call itself, as an [McpError]: a tool the server does not know, a server
     * that did not answer 200, or one that needs something this client cannot give.
     */
    fun callTool(name: String, arguments: JsonObject = JsonObject()): McpToolResult

    companion object {
        /**
         * A client of the server at [url] over Streamable HTTP. [headers] go in every request, like the credentials
         * of an API key; they are not a place for secrets written in code, which come from the environment.
         */
        fun http(
            url: String,
            headers: Map<String, String> = emptyMap(),
            httpClient: HttpClient = defaultHttpClient,
        ): McpClient = HttpMcpClient(url, headers, httpClient)
    }
}
