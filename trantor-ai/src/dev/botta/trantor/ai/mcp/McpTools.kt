package dev.botta.trantor.ai.mcp

import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSerializer
import java.security.MessageDigest
import dev.botta.json.Json as BottaJson

/**
 * The tools of the server, as tools of a generation or an agent:
 *
 * ```kotlin
 * val tools = github.tools(
 *     only = setOf("search_issues", "get_issue", "create_issue"),
 *     readOnly = setOf("search_issues", "get_issue"),
 *     needsApproval = setOf("create_issue"),
 * )
 * agents.run(support) { tools(*tools.toTypedArray()) }
 * ```
 *
 * Each one is called after the client and the tool, like `github_search_issues`, with what the providers do not
 * take in a name turned into `_`. The server is asked for them every time, so the application decides when: once
 * when it starts, or on every run to see the tools it adds.
 *
 * [only], [readOnly] and [needsApproval] name the tools as the server does. A name the server does not have fails,
 * so that a typo does not quietly leave a tool without approval. What the server says of a tool, like
 * `readOnlyHint`, decides nothing: the spec says not to trust it unless the server is trusted, and trusting is the
 * application's to decide here.
 *
 * @throws McpError when a name is not one of the server, or when two tools end up with the same name.
 */
fun McpClient.tools(
    only: Set<String>? = null,
    readOnly: Set<String> = emptySet(),
    needsApproval: Set<String> = emptySet(),
): List<McpTool> {
    val definitions = listTools()
    val known = definitions.map { it.name }.toSet()
    val unknown = only.orEmpty() + readOnly + needsApproval - known

    if (unknown.isNotEmpty()) {
        throw McpError(
            "The MCP server $name has no tool ${unknown.joinToString()}. Its tools are ${known.joinToString()}",
        )
    }

    val tools = definitions.filter { only == null || it.name in only }.map {
        McpTool(this, it, McpToolNames.of(name, it.name), it.name in readOnly, it.name in needsApproval)
    }

    val clashes = tools.groupBy { it.name }.filterValues { it.size > 1 }
    if (clashes.isNotEmpty()) {
        val told = clashes.entries.joinToString("; ") { (name, same) ->
            "${same.joinToString(" and ") { it.definition.name }} are all called $name"
        }
        throw McpError("Some tools of the MCP server ${this.name} end up with the same name: $told")
    }

    return tools
}

/** A tool of an MCP server. The model knows it by [name], and the server by the name of its [definition]. */
class McpTool internal constructor(
    val client: McpClient,
    val definition: McpToolDefinition,
    override val name: String,
    override val readOnly: Boolean,
    private val approval: Boolean,
): Tool<JsonObject>() {
    override val description = definition.description ?: definition.title ?: ""

    override fun needsApproval(args: JsonObject, context: ToolContext) = approval

    /**
     * The schema as the server wrote it, not strictly: strict mode would ask the provider to rewrite a schema this
     * client did not write, and to fill in fields the server may want left out.
     */
    override fun spec(serializer: JsonSerializer) =
        FunctionToolSpec(name, description.ifEmpty { null }, definition.inputSchema, strict = false)

    /**
     * Calls the server. A tool that failed reaches the model as a [ToolError] with what the server said, so that it
     * can fix the call; a call the server turned down is an [McpError], which the model reads as any failure.
     */
    override fun execute(args: JsonObject, context: ToolContext): ToolResult {
        // Within the timeout and the cancellation of the run, and on the span of this tool
        val result = McpTelemetry.calledByTool { client.callTool(definition.name, args, context.callOptions) }
        val texts = result.content.filterIsInstance<McpContent.Text>()

        if (result.isError) throw ToolError(textOf(result).ifEmpty { "The tool ${definition.name} failed" })

        return if (texts.isEmpty() && result.structuredContent != null) {
            ToolResult.json(result.structuredContent)
        } else {
            ToolResult.text(textOf(result))
        }
    }

    /**
     * The text of the answer, and a line for what the model cannot get, like an image, so that it knows there was
     * something instead of the answer looking incomplete.
     */
    private fun textOf(result: McpToolResult) = result.content.mapIndexed { i, content ->
        when (content) {
            is McpContent.Text -> content.text
            is McpContent.Other -> {
                val also = if (i > 0) "also " else ""
                "[The tool ${also}answered with ${content.type} content, which is not shown here]"
            }
        }
    }.joinToString("\n")
}

/** The name a model gets for a tool of an MCP server. */
internal object McpToolNames {
    /** What OpenAI and Anthropic take: `^[a-zA-Z0-9_-]{1,128}$`, checked against both on 2026-09-28. */
    private const val MAX_LENGTH = 128
    private val NOT_TAKEN = Regex("[^a-zA-Z0-9_-]")

    fun of(client: String, tool: String): String {
        val full = "${client}_$tool"
        val name = full.replace(NOT_TAKEN, "_")
        if (name.length <= MAX_LENGTH) return name

        // Cut, with a hash of the whole name so that two long names that start the same stay apart
        val hash = MessageDigest.getInstance("SHA-256").digest(full.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

        return name.take(MAX_LENGTH - hash.length - 1) + "_" + hash
    }
}
