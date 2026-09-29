package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.TrantorBuildInfo

/**
 * The JSON-RPC messages of MCP and what they carry, whatever the transport: the requests the client sends and the
 * results it reads back.
 */
internal object McpMessages {
    /**
     * HeaderMismatch, MissingRequiredClientCapability and UnsupportedProtocolVersion: errors of 2026-07-28 that a
     * server of before does not answer, so one of them says the server speaks the new revision.
     */
    val CURRENT_ERRORS = setOf(
        McpProtocol.Errors.HEADER_MISMATCH,
        McpProtocol.Errors.MISSING_REQUIRED_CLIENT_CAPABILITY,
        McpProtocol.Errors.UNSUPPORTED_PROTOCOL_VERSION,
    )

    /**
     * A request of the 2026-07-28 revision, which says in its `_meta` who asks and on which version, and carries
     * the context of the [trace].
     */
    fun request(
        id: Long,
        method: String,
        params: JsonObject = JsonObject(),
        trace: Map<String, String> = emptyMap(),
    ): JsonObject {
        val meta = meta().apply { putAll(trace.mapValues { Json.value(it.value) }) }
        return Json.obj(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to JsonObject(params.toList()).with("_meta", meta),
        )
    }

    /**
     * A request of a revision before 2026-07-28, which says nothing of who asks, since the handshake did. The context
     * of the [trace] goes in its `_meta` too: a server that does not know it leaves it.
     */
    fun earlierRequest(
        id: Long,
        method: String,
        params: JsonObject = JsonObject(),
        trace: Map<String, String> = emptyMap(),
    ): JsonObject {
        val withTrace = if (trace.isEmpty()) params else JsonObject(params.toList()).with("_meta", Json.value(trace))
        return Json.obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to withTrace)
    }

    /** The handshake of the revisions before 2026-07-28, offering no capabilities. */
    fun initialize(id: Long): JsonObject {
        val params = Json.obj(
            "protocolVersion" to McpProtocol.EARLIER_VERSION,
            "capabilities" to Json.obj(),
            "clientInfo" to clientInfo(),
        )

        return earlierRequest(id, "initialize", params)
    }

    fun notification(method: String) = Json.obj("jsonrpc" to "2.0", "method" to method)

    /** Tells the server that the request [id] is no longer awaited, so it can stop what it does for it. */
    fun cancelled(id: Long, reason: String = "Cancelled by the client") =
        notification("notifications/cancelled").with("params", Json.obj("requestId" to id, "reason" to reason))

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

    fun errorOf(error: JsonObject, what: String, status: Int? = null): McpError {
        val code = error["code"]?.asInt()
        val message = error["message"]?.asString() ?: "The MCP server failed to answer $what"
        val supported = error["data"]?.asObject()?.get("supported")?.asArray()?.mapNotNull { it.asString() }

        // Its own message names the version it was asked for; which ones it takes is what says what to do
        val told = if (code == McpProtocol.Errors.UNSUPPORTED_PROTOCOL_VERSION && supported != null) {
            val speaks = McpProtocol.VERSION
            "The MCP server supports ${supported.joinToString()}, and this client speaks $speaks: $message"
        } else {
            message
        }

        return McpError(told, code, status, error["data"])
    }

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
        McpProtocol.Meta.PROTOCOL_VERSION to McpProtocol.VERSION,
        McpProtocol.Meta.CLIENT_INFO to clientInfo(),
        McpProtocol.Meta.CLIENT_CAPABILITIES to Json.obj(),
    )

    private fun clientInfo() = Json.obj("name" to "trantor-ai", "version" to TrantorBuildInfo.version)
}
