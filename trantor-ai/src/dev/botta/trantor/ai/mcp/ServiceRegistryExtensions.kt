package dev.botta.trantor.ai.mcp

import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.hosting.addHostedService
import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.web.client.addHttpClient
import io.opentelemetry.api.OpenTelemetry

/**
 * Registers [McpClients], with a client for each server of the `ai.mcp.servers` section ([McpSettings]) and the ones
 * the application adds in code, whichever order the calls go:
 *
 * ```kotlin
 * services.addMcp()
 * services.addMcp { clients, _ -> clients.add(McpClient.http("crm", crmUrl)) }
 * ```
 *
 * The clients over HTTP call through the [HttpClient] of the application, which this adds when there is none, so
 * they share its connections and are traced along with every other call; the requests are traced with the
 * `OpenTelemetry` of the container, when there is one. When the application stops, every client is closed: a server
 * over stdio is a process, and it would outlive the application otherwise.
 */
fun ServiceRegistry.addMcp(configuration: ServiceConfiguration<McpClients> = { _, _ -> }) = apply {
    addMcpConfig()
    configure(configuration)

    if (has<McpClients>()) return@apply

    if (!has<HttpClient>()) addHttpClient()
    addSingleton {
        // Whether it was registered before or after, as addAI does
        val openTelemetry = it.getOrDefault<OpenTelemetry> { OpenTelemetry.noop() }
        McpClients().addAll(it.get<McpSettings>(), it.get<HttpClient>(), openTelemetry)
    }
    addHostedService { McpClientsLifetime(it.get<McpClients>()) }
}

/** The settings alone, for an application that builds its clients by hand. */
fun ServiceRegistry.addMcpConfig() = apply {
    if (has<McpSettings>()) return@apply

    addConfig<McpSettings>(SECTION)
}

/** Closes the clients when the application stops. They start on their own when used, so starting does nothing. */
internal class McpClientsLifetime(private val clients: McpClients): HostedService {
    override val name = "MCP clients"

    override fun start() {}

    override fun stop(timeoutSeconds: Int) = clients.close()
}

private const val SECTION = "ai.mcp"
