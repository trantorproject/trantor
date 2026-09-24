package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.ToolOutput

/**
 * How an agent reads the turns other agents of its team took in the conversation: as context, with their names, and
 * not as answers of its own. It is what each step of an agent sends; what the conversation keeps stays as it was.
 *
 * Sent as they are, the answers of the agent before read as the new agent's own, calls included, and those calls are
 * to tools it does not have. The recordings of the handoffs showed the new agent confused in four runs out of five:
 * one ended the run saying it would pass the question to sales, being sales, and others distrusted a result because
 * they "had called a tool they did not have".
 *
 * So a turn of another agent — its answer, and the results of its calls that come right after — goes as a user
 * message that tells what it said and did, line by line, and turns of other agents in a row go as one message. It is
 * what Google ADK does. Its reasoning does not go: it is that agent's, and the provider could not take it anyway
 * when the other agent ran on another model. The lines are in English, as everything the library tells the model.
 *
 * Only turns signed by another agent are told. One without an agent, written by a generation or kept from before the
 * conversation had agents, goes as it is, since there is no telling whose it is.
 */
internal object OtherAgentsTurns {
    const val PREAMBLE = "For context, this is what other agents of your team said and did in the conversation " +
        "before it came to you. It was not you, and their tools may not be yours:"

    /** [messages] as [agent] reads them. */
    fun toldTo(agent: String, messages: List<Message>): List<Message> {
        val told = mutableListOf<Message>()
        val lines = mutableListOf<String>()

        fun tell() {
            if (lines.isEmpty()) return

            told.add(Message.User((listOf(PREAMBLE) + lines).map { TextPart(it) }))
            lines.clear()
        }

        // The other agent whose turn came last, whose calls the results that follow answer
        var other: String? = null

        for (message in messages) {
            val answered = other

            when {
                message is Message.Assistant && message.agent != null && message.agent != agent -> {
                    other = message.agent
                    lines.addAll(linesOf(message.agent, message.parts))
                }
                message is Message.Tool && answered != null -> lines.addAll(linesOf(answered, message.results))
                else -> {
                    tell()
                    told.add(message)
                    other = null
                }
            }
        }

        tell()

        return told
    }

    private fun linesOf(agent: String, parts: List<Part>) = parts.mapNotNull { part ->
        when (part) {
            is TextPart -> "[$agent] said: ${part.text}"
            is RefusalPart -> "[$agent] refused: ${part.text}"
            is ToolCallPart -> "[$agent] called ${part.toolName} with ${part.input}"
            is ToolResultPart -> resultLine(agent, part)
            is ProviderPart -> "[$agent] used ${part.type}: ${part.raw}"
            is ReasoningPart -> null
        }
    }

    private fun resultLine(agent: String, result: ToolResultPart): String {
        val output = when (val output = result.output) {
            is ToolOutput.Text -> output.value
            is ToolOutput.Json -> output.value.toString()
        }

        return if (result.isError) "[$agent] got an error from ${result.toolName}: $output"
        else "[$agent] got from ${result.toolName}: $output"
    }
}
