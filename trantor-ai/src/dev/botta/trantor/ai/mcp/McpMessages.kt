package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.TrantorBuildInfo

/**
 * The JSON-RPC messages of MCP and what they carry, whatever the transport: the requests the client sends and the
 * results it reads back.
 */
internal object McpMessages {
    const val PROTOCOL_VERSION = "2026-07-28"

    /** A request of the 2026-07-28 revision, which says in its `_meta` who asks and on which version. */
    fun request(id: Long, method: String, params: JsonObject = JsonObject()) = Json.obj(
        "jsonrpc" to "2.0",
        "id" to id,
        "method" to method,
        "params" to JsonObject(params.toList()).with("_meta", meta()),
    )

    /**
     * The result of the answer to [request], or the error it carries. [what] names the request in the errors, like
     * "tools/call deploy".
     */
    fun resultOf(answer: JsonObject, what: String, status: Int? = null): JsonObject {
        answer["error"]?.asObject()?.let { throw errorOf(it, what, status) }

        val result = answer["result"]?.asObject() ?: throw McpError("The MCP server answered $what without a result")

        // A server that needs something first answers with what it needs, and waits for the request again with it.
        // Nothing it can ask for is something this client offers, so a server that asks anyway is not answered.
        if (result["resultType"]?.asString() == "input_required") {
            val needs = result["inputRequests"]?.asObject()?.values.orEmpty()
                .mapNotNull { it.asObject()?.get("method")?.asString() }

            throw McpError(
                "The MCP server needs ${needs.joinToString()} to answer $what, which this client cannot give yet",
            )
        }

        return result
    }

    fun errorOf(error: JsonObject, what: String, status: Int? = null) = McpError(
        error["message"]?.asString() ?: "The MCP server failed to answer $what",
        code = error["code"]?.asInt(),
        status = status,
        data = error["data"],
    )

    fun toolOf(tool: JsonObject) = McpToolDefinition(
        name = tool["name"]?.asString() ?: throw McpError("The MCP server listed a tool without a name: $tool"),
        title = tool["title"]?.asString(),
        description = tool["description"]?.asString(),
        // The spec requires it, and a tool without arguments has an empty object: that is what it would mean
        inputSchema = tool["inputSchema"]?.asObject() ?: Json.obj("type" to "object"),
        outputSchema = tool["outputSchema"]?.asObject(),
        annotations = tool["annotations"]?.asObject(),
    )

    fun toolResultOf(result: JsonObject) = McpToolResult(
        content = result["content"]?.asArray().orEmpty().mapNotNull { it.asObject()?.let(::contentOf) },
        structuredContent = result["structuredContent"],
        isError = result["isError"]?.asBoolean() == true,
    )

    private fun contentOf(content: JsonObject): McpContent {
        val type = content["type"]?.asString().orEmpty()
        val text = content["text"]?.asString()

        return if (type == "text" && text != null) McpContent.Text(text) else McpContent.Other(type, content)
    }

    private fun meta() = Json.obj(
        "io.modelcontextprotocol/protocolVersion" to PROTOCOL_VERSION,
        "io.modelcontextprotocol/clientInfo" to Json.obj("name" to "trantor-ai", "version" to TrantorBuildInfo.version),
        "io.modelcontextprotocol/clientCapabilities" to Json.obj(),
    )
}
