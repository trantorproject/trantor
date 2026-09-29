@file:Suppress("ClassName")

package dev.botta.trantor.mcp.server

import dev.botta.cqbus.CQBus
import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.Middleware
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Command
import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.Request
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler
import dev.botta.json.Json
import dev.botta.trantor.ai.mcp.McpError
import dev.botta.trantor.core.auth.NotAuthenticatedError
import dev.botta.trantor.core.auth.RolesAuthorization
import dev.botta.trantor.core.auth.middlewares.RolesAuthorizationMiddleware
import dev.botta.trantor.core.validation.ValidationMiddleware
import dev.botta.trantor.web.auth.SessionTokenFromHeadersMiddleware
import jakarta.validation.constraints.NotBlank
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.junit.jupiter.api.BeforeEach
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

    @Nested
    inner class `a use case as a tool` {
        @Test
        fun `runs through the middlewares of the application, which tell who asks from the token of the call`() {
            val result = seller.callTool("who_am_i")

            assertThat(result.structuredContent.toString()).isEqualTo("""{"name":"nico"}""")
        }

        @Test
        fun `without a token is a 401, which the client does not take for a server of before`() {
            val anonymous = McpClient.http("store", useCasesUrl)

            assertThatThrownBy { anonymous.callTool("who_am_i") }
                .isInstanceOfSatisfying(McpError::class.java) { assertThat(it.status).isEqualTo(401) }
        }

        @Test
        fun `is validated as any request of the application, and the model reads what failed`() {
            val result = seller.callTool("place_order", Json.obj("sku" to " ", "quantity" to 2))

            assertThat(result.isError).isTrue()
            assertThat(result.content.single()).isEqualTo(McpContent.Text("Validation failed: sku: must not be blank"))
        }

        @Test
        fun `is authorized as any request of the application`() {
            val guest = McpClient.http("store", useCasesUrl, mapOf("Authorization" to "Bearer guest-token"))

            val result = guest.callTool("place_order", Json.obj("sku" to "ABC-1", "quantity" to 2))

            assertThat(result.isError).isTrue()
            assertThat(orders).isEmpty()
        }

        @Test
        fun `runs the use case with what the model sent`() {
            val result = seller.callTool("place_order", Json.obj("sku" to "ABC-1", "quantity" to 2))

            assertThat(result.isError).isFalse()
            assertThat(orders).containsExactly(PlaceOrder("ABC-1", 2))
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

        val bus = app.services.get<CQBus>()
        bus.registerContextAwareHandler<WhoAmI, Me> { ContextAwareRequestHandler { _, context -> whoAsks(context) } }
        bus.registerHandler<PlaceOrder, Unit> { RequestHandler { request, _ -> orders += request } }
        // In the order crafty registers them: the last of a priority is the first to run
        app.registerMiddleware(RolesAuthorizationMiddleware())
        app.registerMiddleware(ValidationMiddleware(app.services.get()))
        app.registerMiddleware(TokenAuthenticationMiddleware())
        app.registerMiddleware(SessionTokenFromHeadersMiddleware())

        app.routes.mcp("/store", name = "store", version = "1.0.0") {
            tool<WhoAmI>("who_am_i", "Who asks")
            tool<PlaceOrder>("place_order", "Places an order")
        }

        app.start()
        url = "http://localhost:$port/mcp"
        useCasesUrl = "http://localhost:$port/store"
        client = McpClient.http("store", url)
        seller = McpClient.http("store", useCasesUrl, mapOf("Authorization" to "Bearer seller-token"))
    }

    @BeforeEach
    fun forgetTheOrders() {
        orders.clear()
    }

    @AfterAll
    fun stopTheApplication() {
        client.close()
        seller.close()
        app.stop(10)
    }

    private lateinit var app: WebApplication
    private lateinit var url: String
    private lateinit var client: McpClient
    private lateinit var useCasesUrl: String
    private lateinit var seller: McpClient
    private val orders = mutableListOf<PlaceOrder>()

    private fun whoAsks(context: ExecutionContext): Me {
        if (!context.identity.isAuthenticated) throw NotAuthenticatedError()
        return Me(context.identity.name)
    }

    class WhoAmI: Query<Me>

    data class Me(val name: String)

    @RolesAuthorization(["seller"])
    data class PlaceOrder(@field:NotBlank val sku: String, val quantity: Int): Command<Unit>

    /** Who asks, by the session token the middleware of Trantor took off the header, as crafty does with its own. */
    class TokenAuthenticationMiddleware: Middleware {
        override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
            when (context["session_token"]) {
                "seller-token" -> context.identity = Person("nico", listOf("seller"))
                "guest-token" -> context.identity = Person("ana", emptyList())
            }
            return next(request)
        }
    }

    class Person(override val name: String, override val roles: List<String>): Identity {
        override val isAuthenticated = true
        override val authenticationType = "token"
        override val properties = emptyMap<String, Any>()
    }

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
