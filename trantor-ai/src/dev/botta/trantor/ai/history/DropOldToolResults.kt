package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.tools.ToolOutput

/**
 * Sends what the old results of the tools said as a short note, and the last [keep] of them whole. The results of
 * tools are usually the bulk of a long conversation and the part the model needs least once it answered with them.
 * The calls stay, and so does every result with its call, so the model still knows what it did.
 *
 * It replaces them [step] at a time, for the same reason [LastMessages] cuts in steps: to keep the start of what is
 * sent the same for a while, which is what the providers cache. So it keeps between [keep] and [keep] + [step] - 1
 * results whole.
 */
class DropOldToolResults(private val keep: Int, private val step: Int = maxOf(1, keep)): ContextPolicy {
    init {
        require(keep >= 0) { "DropOldToolResults keeps no fewer than 0 results, not $keep" }
        require(step >= 1) { "DropOldToolResults replaces at least one result at a time, not $step" }
    }

    override fun project(messages: List<Message>, run: RunContext): List<Message> {
        val results = messages.sumOf { if (it is Message.Tool) it.results.size else 0 }
        val dropping = (results - keep).coerceAtLeast(0) / step * step
        var seen = 0

        if (dropping == 0) return messages

        return messages.map { message ->
            if (message !is Message.Tool) return@map message

            message.copy(
                results = message.results.map { result ->
                    if (seen++ < dropping) result.copy(output = ToolOutput.Text(REMOVED)) else result
                },
            )
        }
    }

    companion object {
        /** What the model reads instead of a result that was dropped. */
        const val REMOVED = "This result was removed to save context."
    }
}
