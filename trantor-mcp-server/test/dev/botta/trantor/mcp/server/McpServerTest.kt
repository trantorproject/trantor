@file:Suppress("ClassName")

package dev.botta.trantor.mcp.server

import dev.botta.json.Json
import dev.botta.trantor.ai.mcp.McpClient
import dev.botta.trantor.ai.mcp.McpContent
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.web.application.WebApplication
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The MCP client of trantor-ai talking to an MCP route of a running application. */
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerTest {
    @Nested
    inner class `a client of trantor-ai` {
        @Test
        fun `lists the tools of the route, with their schemas`() {
            val tools = client.listTools()

            assertThat(tools.map { it.name }).containsExactly("echo", "weather", "refund")
            assertThat(tools.first().inputSchema.path("properties.text.type")?.asString()).isEqualTo("string")
            assertThat(tools.first().annotations?.get("readOnlyHint")?.asBoolean()).isTrue()
        }

        @Test
        fun `calls one and reads what it answered`() {
            val result = client.callTool("echo", Json.obj("text" to "hola"))

            assertThat(result.content).containsExactly(McpContent.Text("hola"))
            assertThat(result.isError).isFalse()
        }

        @Test
        fun `reads the structured content of one that answers json`() {
            val result = client.callTool("weather", Json.obj("city" to "Rosario"))

            assertThat(result.structuredContent.toString()).isEqualTo("""{"city":"Rosario","celsius":18}""")
        }

        @Test
        fun `reads a tool that failed as an error of the tool`() {
            val result = client.callTool("refund", Json.obj("amount" to 500))

            assertThat(result.isError).isTrue()
            assertThat(result.content).containsExactly(McpContent.Text("Refunds over 100 need a manager"))
        }
    }

    @Test
    fun `answers GET with 405, since the server opens no stream of its own`() {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())

        assertThat(response.statusCode()).isEqualTo(405)
    }

    @BeforeAll
    fun startTheApplication() {
        val port = ServerSocket(0).use { it.localPort }
        val builder = WebApplication.builder { appName = "test"; environmentName = "DEVELOPMENT" }
        builder.config.addMemoryCollection("httpServer.port" to port.toString())
        app = builder.build()

        app.routes.mcp("/mcp", name = "store", version = "1.0.0") {
            tool(EchoTool())
            tool(WeatherTool())
            tool(RefundTool())
        }

        app.start()
        url = "http://localhost:$port/mcp"
        client = McpClient.http("store", url)
    }

    @AfterAll
    fun stopTheApplication() {
        client.close()
        app.stop(10)
    }

    private lateinit var app: WebApplication
    private lateinit var url: String
    private lateinit var client: McpClient

    class EchoTool: Tool<EchoTool.Args>(Args.serializer()) {
        override val name = "echo"
        override val description = "Says back what it is given"
        override val readOnly = true

        override fun execute(args: Args, context: ToolContext) = ToolResult.text(args.text)

        @Serializable
        data class Args(val text: String)
    }

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "weather"
        override val description = "The weather of a city"

        override fun execute(args: Args, context: ToolContext) =
            ToolResult.json(Json.obj("city" to args.city, "celsius" to 18))

        @Serializable
        data class Args(val city: String)
    }

    class RefundTool: Tool<RefundTool.Args>(Args.serializer()) {
        override val name = "refund"
        override val description = "Gives the money of an order back"

        override fun execute(args: Args, context: ToolContext): ToolResult {
            if (args.amount > 100) throw ToolError("Refunds over 100 need a manager")
            return ToolResult.text("Refunded")
        }

        @Serializable
        data class Args(val amount: Int)
    }
}
