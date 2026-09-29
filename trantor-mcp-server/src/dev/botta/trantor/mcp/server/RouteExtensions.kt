package dev.botta.trantor.mcp.server

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.web.server.RouteRegister
import io.javalin.http.Context
import io.javalin.http.Handler

/**
 * An MCP server at [path], a route like any other of the application:
 *
 * ```kotlin
 * class SupportMcp(private val products: SearchProductsTool): ApplicationController {
 *     override fun registerRoutes(http: ApplicationRouteRegister) {
 *         http.mcp("/mcp", name = "store", version = "1.0.0") {
 *             tool(products)
 *         }
 *     }
 * }
 * ```
 *
 * It takes POST, as the 2026-07-28 revision of MCP asks, and answers GET and DELETE with 405: there is no stream the
 * server opens on its own and no session to end.
 */
fun <T: RouteRegister> T.mcp(path: String, name: String, version: String, configure: McpEndpointBuilder.() -> Unit) =
    apply {
        val endpoint = McpEndpointBuilder(name, version).apply(configure).build()
        val handler = Handler { respond(it, endpoint.handle(requestOf(it))) }

        post(path, handler)
        get(path, handler)
        delete(path, handler)
    }

/** The tools and the instructions of an MCP endpoint. */
class McpEndpointBuilder internal constructor(private val name: String, private val version: String) {
    private val tools = mutableListOf<Tool<*>>()
    private var instructions: String? = null

    fun tool(tool: Tool<*>) = apply { tools += tool }

    /** What a client should know to use the tools, which it can give to its model. */
    fun instructions(text: String) = apply { instructions = text }

    internal fun build() = McpEndpoint(name, version, tools.toList(), instructions)
}

private fun requestOf(context: Context) = McpHttpRequest(context.method().name, context.body(), context.headerMap())

private fun respond(context: Context, response: McpHttpResponse) {
    context.status(response.status)
    response.headers.forEach { (name, value) -> context.header(name, value) }
    response.body?.let { context.result(it) }
}
