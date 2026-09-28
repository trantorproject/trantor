package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.CallOptions

/** What every transport does the same way: the requests to list the tools, page by page, and to call one. */
internal abstract class BaseMcpClient: McpClient {
    /** What traces the requests, which the transport also tells what only it knows. */
    protected abstract val telemetry: McpTelemetry

    override fun listTools(options: CallOptions): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null

        do {
            val params = cursor?.let { Json.obj("cursor" to it) } ?: JsonObject()
            val result = traced(McpRequest("tools/list", params, options = options))

            result["tools"]?.asArray().orEmpty().mapNotNullTo(tools) { it.asObject()?.let(McpMessages::toolOf) }
            cursor = result["nextCursor"]?.asString()
        } while (cursor != null)

        return listed(tools)
    }

    override fun callTool(name: String, arguments: JsonObject, options: CallOptions): McpToolResult {
        val request = McpRequest("tools/call", Json.obj("name" to name, "arguments" to arguments), name, options)

        return telemetry.request(request) {
            McpMessages.toolResultOf(send(request)).also { if (it.isError) telemetry.toolFailed() }
        }
    }

    private fun traced(request: McpRequest) = telemetry.request(request) { send(request) }

    /** The tools of a whole listing, as the transport takes them, which may leave some out. */
    protected open fun listed(tools: List<McpToolDefinition>) = tools

    /** Sends [request] in the revision the server speaks, and gives back the result of its answer. */
    protected abstract fun send(request: McpRequest): JsonObject
}

/** A request of the client, before it becomes the message of one revision or the other. */
internal class McpRequest(
    val method: String,
    val params: JsonObject,
    val name: String? = null,
    val options: CallOptions = CallOptions(),
) {
    /** How the errors name it, like "tools/call deploy". */
    val what = listOfNotNull(method, name).joinToString(" ")
}
