package dev.botta.trantor.ai.mcp

import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.web.client.HttpClient
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The MCP clients of the application, by the name it calls each server: the ones of the `ai.mcp.servers` section and
 * the ones it adds in code.
 *
 * ```kotlin
 * class SupportAgents(private val mcp: McpClients) {
 *     fun support() = Agent("support")
 *         .tools(*mcp["github"].tools(only = setOf("search_issues")).toTypedArray())
 *         .build()
 * }
 * ```
 *
 * A client connects to nothing until it is used, so a server the application declares and never asks for costs
 * nothing. [close] ends them all, which [addMcp] does when the application stops.
 */
class McpClients {
    private val clients = linkedMapOf<String, McpClient>()

    /** The names of the servers, in the order they were added. */
    val names: List<String>
        @Synchronized get() = clients.keys.toList()

    @Synchronized
    fun add(client: McpClient) = apply {
        if (clients.containsKey(client.name)) throw McpError("There is already an MCP server called ${client.name}")

        clients[client.name] = client
    }

    @Synchronized
    operator fun get(name: String): McpClient {
        val there = clients.keys.joinToString().ifEmpty { "none" }
        return clients[name] ?: throw McpError("There is no MCP server called $name. There are: $there")
    }

    /** Closes every client; one that fails to close does not keep the others from closing. */
    fun close() {
        val all = synchronized(this) { clients.values.toList() }

        for (client in all) {
            try {
                client.close()
            } catch (e: Exception) {
                logger.warn("Could not close the MCP client ${client.name}: ${e.message}", e)
            }
        }
    }

    /** Adds a client for each server of [settings], its HTTP ones calling through [httpClient]. */
    internal fun addAll(settings: McpSettings, httpClient: HttpClient) = apply {
        settings.servers.forEach { (name, server) -> add(clientOf(name, server, httpClient)) }
    }

    private fun clientOf(name: String, server: McpServerSettings, httpClient: HttpClient): McpClient {
        val url = server.url
        val command = server.command.orEmpty()

        return when {
            url != null && command.isNotEmpty() ->
                throw McpError("The MCP server $name has both a url and a command: it is one or the other")
            url != null -> McpClient.http(name, url, server.headers, httpClient)
            command.isNotEmpty() -> McpClient.stdio(
                name,
                command,
                server.env,
                server.workingDirectory?.let(::File),
                server.requestTimeoutSeconds?.seconds ?: StdioMcpClient.DEFAULT_REQUEST_TIMEOUT,
            )
            else -> throw McpError("The MCP server $name has neither a url nor a command")
        }
    }

    private companion object {
        val logger = getLogger<McpClients>()
    }
}
