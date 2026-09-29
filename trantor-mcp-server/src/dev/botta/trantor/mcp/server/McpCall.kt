package dev.botta.trantor.mcp.server

import dev.botta.cqbus.requests.Request

/**
 * The call to a tool of an MCP endpoint, as the tool sees it: the way to run a use case of the application with the
 * HTTP request that brought the call. The middlewares of the application see it as they see the request of any
 * route, so the one that reads the token tells who asks. A tool takes it from its context:
 * `context.run.require<McpCall>()`.
 */
class McpCall(private val run: (Request<*>) -> Any?) {
    /** Runs [request] through the middlewares of the application, and gives what its handler answered. */
    @Suppress("UNCHECKED_CAST")
    fun <R> execute(request: Request<R>): R = run(request) as R
}
