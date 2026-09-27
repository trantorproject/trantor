package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonValue

/** What a tool of an MCP server answered. */
data class McpToolResult(
    /** What the tool said, in order: text, and images, audio or resources as [McpContent.Other]. */
    val content: List<McpContent>,
    /**
     * The same answer as JSON, when the tool gives one. A server that gives it SHOULD also put it as text in
     * [content], for clients that only read that.
     */
    val structuredContent: JsonValue? = null,
    /** The tool failed, and [content] says why, in words a model can act on. */
    val isError: Boolean = false,
)
