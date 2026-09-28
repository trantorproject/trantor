@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.client.HttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** The MCP servers an application declares in `ai.mcp.servers`, or in code, and their clients. */
class McpServiceRegistryExtensionsTest {
    @Nested
    inner class `a server of the configuration` {
        @Test
        fun `with a url is a client over HTTP, with its headers, through the HttpClient of the application`() {
            config.addMemoryCollection(
                "ai.mcp.servers.github.url" to URL,
                "ai.mcp.servers.github.headers.Authorization" to "Bearer \${GITHUB_TOKEN}",
                "GITHUB_TOKEN" to "ghp_123",
            )
            http.answer(javaClass.getResource("/mcp/call-weather.json")!!.readText())
            registry.addSingleton<HttpClient>(http)
            registry.addMcp()

            clients()["github"].callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(http.request?.url).isEqualTo(URL)
            assertThat(http.request?.headers).containsEntry("Authorization", "Bearer ghp_123")
        }

        @Test
        fun `with a command is a client over stdio, with its environment, directory and timeout`() {
            config.addMemoryCollection(
                "ai.mcp.servers.files.command.__config_type__" to "array",
                "ai.mcp.servers.files.command.size" to "3",
                "ai.mcp.servers.files.command.0" to "npx",
                "ai.mcp.servers.files.command.1" to "-y",
                "ai.mcp.servers.files.command.2" to "@modelcontextprotocol/server-filesystem",
                "ai.mcp.servers.files.env.API_KEY" to "secret",
                "ai.mcp.servers.files.workingDirectory" to "servers",
                "ai.mcp.servers.files.requestTimeoutSeconds" to "30",
            )
            registry.addMcp()

            val files = clients()["files"] as StdioMcpClient

            assertThat(files.command).containsExactly("npx", "-y", "@modelcontextprotocol/server-filesystem")
            assertThat(files.env).isEqualTo(mapOf("API_KEY" to "secret"))
            assertThat(files.workingDirectory).isEqualTo(File("servers"))
            assertThat(files.requestTimeout).isEqualTo(30.seconds)
        }

        @Test
        fun `with both a url and a command fails saying which`() {
            config.addMemoryCollection(
                "ai.mcp.servers.files.url" to URL,
                "ai.mcp.servers.files.command.__config_type__" to "array",
                "ai.mcp.servers.files.command.size" to "1",
                "ai.mcp.servers.files.command.0" to "npx",
            )
            registry.addMcp()

            assertThatThrownBy { clients() }.isInstanceOf(McpError::class.java).hasMessageContaining("files")
        }

        @Test
        fun `with neither a url nor a command fails saying which`() {
            config.addMemoryCollection("ai.mcp.servers.files.env.API_KEY" to "secret")
            registry.addMcp()

            assertThatThrownBy { clients() }.isInstanceOf(McpError::class.java).hasMessageContaining("files")
        }

        @Test
        fun `connects to nothing until its client is used`() {
            config.addMemoryCollection("ai.mcp.servers.github.url" to URL)
            registry.addSingleton<HttpClient>(http)
            registry.addMcp()

            clients()["github"]

            assertThat(http.requests).isEmpty()
        }
    }

    @Nested
    inner class `the registry` {
        @Test
        fun `takes the clients an application adds in code, whichever order the calls go`() {
            registry.addMcp { clients, _ -> clients.add(FakeClient("crm")) }
            registry.addMcp()
            registry.addMcp { clients, _ -> clients.add(FakeClient("billing")) }

            assertThat(clients().names).containsExactly("crm", "billing")
        }

        @Test
        fun `fails when two clients have the same name`() {
            config.addMemoryCollection("ai.mcp.servers.crm.url" to URL)
            registry.addMcp { clients, _ -> clients.add(FakeClient("crm")) }

            assertThatThrownBy { clients() }.isInstanceOf(McpError::class.java).hasMessageContaining("crm")
        }

        @Test
        fun `asking for a server that is not there fails naming the ones there are`() {
            registry.addMcp { clients, _ -> clients.add(FakeClient("crm")) }

            assertThatThrownBy { clients()["github"] }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("github")
                .hasMessageContaining("crm")
        }
    }

    @Nested
    inner class `its life` {
        @Test
        fun `every client is closed when the application stops`() {
            val crm = FakeClient("crm")
            val billing = FakeClient("billing")
            registry.addMcp { clients, _ -> clients.add(crm).add(billing) }

            provider.getAll<HostedService>().forEach { it.stop() }

            assertThat(crm.closed).isTrue()
            assertThat(billing.closed).isTrue()
        }

        @Test
        fun `adding it twice is the same as once`() {
            registry.addMcp()
            registry.addMcp()

            assertThat(provider.getAll<HostedService>()).hasSize(1)
            assertThat(provider.getAll<McpClients>()).hasSize(1)
        }
    }

    private fun clients() = provider.get<McpClients>()

    private class FakeClient(override val name: String): McpClient {
        var closed = false

        override fun listTools() = emptyList<McpToolDefinition>()

        override fun callTool(name: String, arguments: JsonObject) = McpToolResult(emptyList())

        override fun close() {
            closed = true
        }
    }

    private val http = FakeHttpClient()
    private val config = ConfigManager()
    private val registry = ServiceRegistry(config).apply { addSingleton<JsonSerializer>(GsonSerializer()) }
    private val provider = DefaultServiceProvider(registry)

    private companion object {
        const val URL = "http://127.0.0.1:3001/mcp"
    }
}
