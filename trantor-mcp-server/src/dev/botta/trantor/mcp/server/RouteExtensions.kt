package dev.botta.trantor.mcp.server

import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.web.application.routes.ApplicationRouteRegister
import dev.botta.trantor.web.server.RouteRegister
import dev.botta.trantor.web.server.clientAddress
import io.javalin.http.Context
import io.javalin.http.Handler
import io.opentelemetry.api.OpenTelemetry
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * An MCP server at [path], a route like any other of the application:
 *
 * ```kotlin
 * class StoreMcp(private val products: SearchProductsTool): ApplicationController {
 *     override fun registerRoutes(http: ApplicationRouteRegister) {
 *         http.mcp("/mcp", name = "store", version = "1.0.0") {
 *             tool<PlaceOrder>("place_order", "Places an order for a customer")
 *             tool(products)
 *         }
 *     }
 * }
 * ```
 *
 * A tool can be a use case of the application, which runs as a route that is a use case does: through its
 * middlewares, with the HTTP request of the call. That needs the routes of an `ApplicationController`, which know how
 * to run one; on the routes of a plain `Controller`, only tools written by hand can be served.
 *
 * It takes POST, as the 2026-07-28 revision of MCP asks, and answers GET and DELETE with 405: there is no stream the
 * server opens on its own and no session to end.
 *
 * It traces with the `OpenTelemetry` of the routes, the one of the server: each request is a span of MCP that goes on
 * from the trace the client sent in it, as [McpEndpoint] tells.
 */
fun <T: RouteRegister> T.mcp(path: String, name: String, version: String, configure: McpEndpointBuilder.() -> Unit) =
    apply {
        val application = this as? ApplicationRouteRegister
        val endpoint = McpEndpointBuilder(name, version, application?.mapper?.serializer, openTelemetry)
            .apply(configure)
            .build()

        val handler = Handler { context ->
            val call = McpCall { request -> execute(application, request, context) }
            respond(context, endpoint.handle(requestOf(context), RunContext(call)))
        }

        post(path, handler)
        get(path, handler)
        delete(path, handler)
    }

/** The tools and the instructions of an MCP endpoint. */
class McpEndpointBuilder internal constructor(
    private val name: String,
    private val version: String,
    private val serializer: JsonSerializer?,
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
    private val tools = mutableListOf<Tool<*>>()
    private var instructions: String? = null
    private var authenticated = false
    private var visibleTools: ((List<Tool<*>>, Identity) -> List<Tool<*>>)? = null

    fun tool(tool: Tool<*>) = apply { tools += tool }

    /**
     * The use case [T] as a tool: its schema is what the serializer of the application reads as [T], and it runs as
     * a route that is a use case does. It only reads when [readOnly] says so, or else when [T] is a `Query`.
     *
     * @throws McpServerError when the endpoint is not on the routes of an `ApplicationController`, or the serializer
     * of the application cannot say the schema of [T].
     */
    inline fun <reified T: Request<*>> tool(name: String, description: String, readOnly: Boolean? = null) =
        tool(typeOf<T>(), name, description, readOnly)

    fun tool(type: KType, name: String, description: String, readOnly: Boolean? = null) = apply {
        val serializer = serializer ?: throw McpServerError(
            "The tool $name runs the use case $type, which needs the routes of an ApplicationController: " +
                "declare the MCP endpoint in one, whose routes know how to run a use case",
        )

        tools += UseCaseTool.of(type, name, description, serializer, readOnly)
    }

    /** What a client should know to use the tools, which it can give to its model. */
    fun instructions(text: String) = apply { instructions = text }

    /**
     * Answers every request whose caller the middlewares of the application did not authenticate with 401 and the
     * challenge of a Bearer token, before anything else: that is what makes a client ask the person to sign in.
     */
    fun requireAuthentication() = apply { authenticated = true }

    /**
     * Which of the tools the caller sees, given the identity the middlewares of the application built: the ones
     * [filter] gives. It is asked once for the whole list, on every listing and every call, so it can read what each
     * one may use from a database in one query. A tool the caller does not see does not exist for them: calling it
     * answers that there is no such tool. Hiding a tool is not authorizing it: the use case still does that.
     */
    fun visibleTools(filter: (tools: List<Tool<*>>, identity: Identity) -> List<Tool<*>>) =
        apply { visibleTools = filter }

    internal fun build() =
        McpEndpoint(name, version, tools.toList(), instructions, authenticated, visibleTools, openTelemetry)
}

private fun execute(application: ApplicationRouteRegister?, request: Request<*>, context: Context): Any? {
    val executor = application?.executor
        ?: throw McpServerError("A use case can only run from the routes of an ApplicationController")

    @Suppress("UNCHECKED_CAST")
    return executor.execute(request as Request<Any?>, context)
}

private fun requestOf(context: Context) = McpHttpRequest(
    context.method().name,
    context.body(),
    context.headerMap(),
    clientAddress = context.req().clientAddress,
    clientPort = context.req().remotePort,
    httpVersion = context.req().protocol.substringAfter("HTTP/"),
)

private fun respond(context: Context, response: McpHttpResponse) {
    context.status(response.status)
    response.headers.forEach { (name, value) -> context.header(name, value) }
    response.body?.let { context.result(it) }
}
