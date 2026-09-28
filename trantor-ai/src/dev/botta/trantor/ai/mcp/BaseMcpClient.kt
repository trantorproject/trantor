package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject

/** What every transport does the same way: the requests to list the tools, page by page, and to call one. */
internal abstract class BaseMcpClient: McpClient {
    override fun listTools(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null

        do {
            val params = cursor?.let { Json.obj("cursor" to it) } ?: JsonObject()
            val result = send(McpRequest("tools/list", params))

            result["tools"]?.asArray().orEmpty().mapNotNullTo(tools) { it.asObject()?.let(McpMessages::toolOf) }
            cursor = result["nextCursor"]?.asString()
        } while (cursor != null)

        return tools
    }

    override fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val params = Json.obj("name" to name, "arguments" to arguments)
        return McpMessages.toolResultOf(send(McpRequest("tools/call", params, name)))
    }

    /** Sends [request] in the revision the server speaks, and gives back the result of its answer. */
    protected abstract fun send(request: McpRequest): JsonObject
}

/** A request of the client, before it becomes the message of one revision or the other. */
internal class McpRequest(val method: String, val params: JsonObject, val name: String? = null) {
    /** How the errors name it, like "tools/call deploy". */
    val what = listOfNotNull(method, name).joinToString(" ")
}
