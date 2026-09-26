package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ToolCallPart

/**
 * Keeps every call of what a step sends with its result, and every result with its call. The providers reject a
 * request with one and not the other, and what a step sends of the conversation can break a pair: a context policy
 * cut between them, or a history came in broken. The half left alone is taken out, with a warning, so the request
 * goes; a message left with nothing goes out too.
 *
 * A call the provider ran carries its result within the answer, so it has no result to look for.
 */
internal object ToolPairs {
    fun matched(messages: List<Message>): Pair<List<Message>, List<ModelWarning>> {
        val calls = messages.filterIsInstance<Message.Assistant>()
            .flatMap { it.parts.filterIsInstance<ToolCallPart>() }
            .filterNot { it.providerExecuted }
        val results = messages.filterIsInstance<Message.Tool>().flatMap { it.results }
        val callIds = calls.map { it.callId }.toSet()
        val resultIds = results.map { it.callId }.toSet()
        val lonelyCalls = calls.filter { it.callId !in resultIds }
        val lonelyResults = results.filter { it.callId !in callIds }

        if (lonelyCalls.isEmpty() && lonelyResults.isEmpty()) return messages to emptyList()

        val warnings = lonelyCalls.map {
            ModelWarning(
                "What the step sent had the call ${it.callId} to ${it.toolName} without its result, so the call was " +
                    "left out",
            )
        } + lonelyResults.map {
            ModelWarning(
                "What the step sent had the result of ${it.callId} to ${it.toolName} without its call, so the result " +
                    "was left out",
            )
        }
        val matched = messages.mapNotNull { message ->
            when (message) {
                is Message.Assistant -> message.copy(parts = message.parts.filterNot { it in lonelyCalls })
                    .takeIf { it.parts.isNotEmpty() }
                is Message.Tool -> message.copy(results = message.results.filterNot { it in lonelyResults })
                    .takeIf { it.results.isNotEmpty() }
                else -> message
            }
        }

        return matched to warnings
    }
}
