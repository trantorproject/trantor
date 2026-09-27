package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.errors.AIError

/**
 * A request to an MCP server that failed: the server answered with an error, did not answer 200, or asked for
 * something this client cannot give. A tool that ran and failed is not one: it comes back as a result with
 * [McpToolResult.isError].
 */
open class McpError(
    message: String,
    /** The JSON-RPC code of the error, when the server gave one, like -32602 for a tool it does not know. */
    val code: Int? = null,
    /** The HTTP status, when the server did not answer 200. */
    val status: Int? = null,
    /** What else the server said about the error. */
    val data: JsonValue? = null,
    cause: Throwable? = null,
): AIError(message, cause)
