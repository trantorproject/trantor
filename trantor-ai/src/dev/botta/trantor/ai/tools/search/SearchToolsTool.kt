package dev.botta.trantor.ai.tools.search

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.primitives.serialization.Description

/**
 * The tool a model searches the searchable tools of a run with, where it has no tool search of its own. It answers
 * the names and descriptions of the ones found, not their schemas: the loop tells the model about them in the next
 * step, like any other tool, which is why a model cannot call one in the same answer it searched in.
 */
internal class SearchToolsTool(private val searchable: List<Tool<*>>): Tool<SearchToolsTool.Args>() {
    override val name = NAME
    override val description = "Searches the tools you do not see yet, by what they do. Search with the words the " +
        "tools would use, which may be in another language than the conversation. The ones found can be called from " +
        "your next answer on."
    override val readOnly = true

    /**
     * The ones found, by name and description. When none is, the names of all of them, up to [NAMES_WHEN_NOTHING]:
     * a model that searches in the language of the conversation finds nothing in tools named in another one, and
     * gives up; with their names it searches again in their words.
     */
    override fun execute(args: Args, context: ToolContext): ToolResult {
        val found = ToolSearch(searchable.map { it.spec(context.serializer) }).search(args.query)
        val tools = found.map { spec ->
            Json.obj("name" to spec.name).apply { spec.description?.let { this["description"] = it } }
        }
        val answer = Json.obj("tools" to Json.array(tools))

        if (found.isEmpty()) answer["names"] = Json.array(searchable.take(NAMES_WHEN_NOTHING).map { it.name })

        return ToolResult.json(answer)
    }

    data class Args(@Description("Words of what the tool you need does, like \"weather forecast\"") val query: String)

    companion object {
        const val NAME = "search_tools"
        private const val NAMES_WHEN_NOTHING = 100

        /**
         * The names of the tools [conversation] says were found: the ones in the answers of this tool. That is how a
         * tool found stays found in the next run of a conversation, and is lost once the answer that found it is
         * compacted away, when the model searches again.
         */
        fun found(conversation: List<Message>): Set<String> = conversation.asSequence()
            .filterIsInstance<Message.Tool>()
            .flatMap { it.results.asSequence() }
            .filter { it.toolName == NAME && !it.isError }
            .mapNotNull { (it.output as? ToolOutput.Json)?.value?.asObject()?.get("tools")?.asArray() }
            .flatMap { tools -> tools.mapNotNull { it.asObject()?.get("name")?.asString() } }
            .toSet()
    }
}
