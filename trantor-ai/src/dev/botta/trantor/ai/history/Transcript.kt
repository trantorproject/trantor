package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.ToolOutput

/**
 * A conversation told line by line — who said what, called what with what, and got what — for a model that reads
 * it as context and not as turns of its own: the turns of another agent, or the old part of a conversation to
 * summarize. The lines are in English, as everything the library tells the model.
 *
 * Reasoning is not told: it belongs to whoever reasoned, and a signed one means nothing to anybody else.
 */
internal object Transcript {
    /** [messages] as lines, the results of the tools told as got by the author of the calls before them. */
    fun of(messages: List<Message>): List<String> {
        var author = ASSISTANT

        return messages.flatMap { message ->
            when (message) {
                is Message.System -> listOf("[system] said: ${message.text}")
                is Message.Summary -> listOfNotNull(message.text?.let { "[summary of what came before] $it" })
                is Message.User -> linesOf(USER, message.parts)
                is Message.Assistant -> {
                    author = message.agent ?: ASSISTANT
                    linesOf(author, message.parts)
                }
                is Message.Tool -> message.results.map { resultLine(author, it) }
            }
        }
    }

    fun linesOf(who: String, parts: List<Part>) = parts.mapNotNull { part ->
        when (part) {
            is TextPart -> "[$who] said: ${part.text}"
            is RefusalPart -> "[$who] refused: ${part.text}"
            is ToolCallPart -> "[$who] called ${part.toolName} with ${part.input}"
            is ToolResultPart -> resultLine(who, part)
            is ProviderPart -> "[$who] used ${part.type}: ${part.raw}"
            is ReasoningPart -> null
        }
    }

    fun resultLine(who: String, result: ToolResultPart): String {
        val output = when (val output = result.output) {
            is ToolOutput.Text -> output.value
            is ToolOutput.Json -> output.value.toString()
        }

        return if (result.isError) "[$who] got an error from ${result.toolName}: $output"
        else "[$who] got from ${result.toolName}: $output"
    }

    private const val USER = "user"
    private const val ASSISTANT = "assistant"
}
