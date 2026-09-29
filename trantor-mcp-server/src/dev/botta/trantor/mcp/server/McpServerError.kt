package dev.botta.trantor.mcp.server

/** An MCP endpoint that cannot be built as it was declared, said when the application starts. */
open class McpServerError(message: String, cause: Throwable? = null): Exception(message, cause)
