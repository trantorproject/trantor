package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.providers.defaultHttpClient
import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.ai.models.CallOptions
import io.opentelemetry.api.OpenTelemetry
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration

/**
 * A connection to one MCP server, to list the tools it offers and call them.
 *
 * It speaks the 2026-07-28 revision of the protocol, where every request stands on its own, and the revisions before
 * it, which most servers still speak: with one of those it opens a session with their handshake on the first request,
 * and opens it again if the server loses it. [close] ends that session. It declares no capabilities of its own, so a
 * server does not ask it for input, a completion of a model or its roots.
 *
 * ```kotlin
 * val github = McpClient.http("github", "https://api.githubcopilot.com/mcp/", mapOf("Authorization" to "Bearer $key"))
 *
 * github.listTools()
 * github.callTool("search_issues", Json.obj("query" to "is:open label:bug"))
 * ```
 *
 * Listing is a call to the server, so it happens when it is asked for and not when the client is created.
 */
interface McpClient: AutoCloseable {
    /**
     * How the application calls the server, like "github". It goes in front of the names of its tools, so that two
     * servers can have a tool with the same name. It is not the name the server says it has, which nothing checks.
     */
    val name: String

    /** Every tool of the server, following its pages until the last one. */
    fun listTools(options: CallOptions = CallOptions()): List<McpToolDefinition>

    /**
     * Calls a tool of the server with [arguments]. A tool that failed comes back with [McpToolResult.isError], as the
     * server tells it; what fails is the call itself, as an [McpError]: a tool the server does not know, a server
     * that did not answer 200, or one that needs something this client cannot give.
     */
    fun callTool(
        name: String,
        arguments: JsonObject = JsonObject(),
        options: CallOptions = CallOptions(),
    ): McpToolResult

    companion object {
        /**
         * A client of the server at [url] over Streamable HTTP, which the application calls [name]. [headers] go in
         * every request, like the credentials of an API key; they are not a place for secrets written in code, which
         * come from the environment.
         */
        fun http(
            name: String,
            url: String,
            headers: Map<String, String> = emptyMap(),
            httpClient: HttpClient = defaultHttpClient,
            requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
            openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
        ): McpClient = HttpMcpClient(name, url, headers, httpClient, requestTimeout, openTelemetry)

        /**
         * A client of a server it starts as a process with [command], and talks to over its standard input and
         * output, which the application calls [name]. It starts with the first request, and [close] ends it.
         *
         * The process gets only the safe part of the environment of the application, like the path, and [env]:
         * the keys of the application are not for a server of a third party, and what it needs goes in [env].
         * On Windows, a bare command like `npx` is found on the path with its extension, `npx.cmd`, so the same
         * command works on every system. A request that gets no answer in [requestTimeout] fails, and the server is
         * told to stop it.
         *
         * ```kotlin
         * val files = McpClient.stdio("files", listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"))
         * ```
         */
        fun stdio(
            name: String,
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            workingDirectory: File? = null,
            requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
            openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
        ): McpClient =
            StdioMcpClient(name, command, env, workingDirectory, requestTimeout, openTelemetry = openTelemetry)

        /** How long a request waits for its answer when neither the client nor the run says. */
        val DEFAULT_REQUEST_TIMEOUT = 2.minutes
    }
}
