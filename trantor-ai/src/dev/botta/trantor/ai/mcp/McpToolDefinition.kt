package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonObject

/** A tool as an MCP server describes it. */
data class McpToolDefinition(
    /** Unique only within its server: two servers can both have a `search`. */
    val name: String,
    /** A name for people, to show in a UI. */
    val title: String? = null,
    val description: String? = null,
    /** The JSON Schema of the arguments, as the server wrote it: JSON Schema 2020-12 unless it says otherwise. */
    val inputSchema: JsonObject,
    /** The JSON Schema of [McpToolResult.structuredContent], when the tool has one. */
    val outputSchema: JsonObject? = null,
    /**
     * Hints about what the tool does, like `readOnlyHint` or `destructiveHint`. The spec says they are not to be
     * trusted unless the server is, so they decide nothing on their own.
     */
    val annotations: JsonObject? = null,
)
