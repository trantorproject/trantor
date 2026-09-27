package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonObject

/** A piece of what a tool of an MCP server answered. */
sealed interface McpContent {
    data class Text(val text: String): McpContent

    /** Anything that is not text yet, like an image, audio, a link to a resource or a resource, as it came. */
    data class Other(val type: String, val json: JsonObject): McpContent
}
