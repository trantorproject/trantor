package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.cost.CostEstimate

/**
 * What a run left: the answer, every step it took to get there and what it all cost.
 *
 * The answer is the last step's. The usage, the estimated cost, the warnings and the tool failures are those of
 * every step, since a run with tools pays for each call to the model and not only for the one that answered.
 */
data class RunResult(val steps: List<Step>) {
    /** The response of the last step, the one that answered. */
    val response get() = steps.last().response

    val text get() = response.text

    val finishReason get() = response.finishReason

    /**
     * What the run added to the conversation, for the application to keep and send in the next request: every
     * answer of the model and the results of its tools, in order, without what the request already had.
     *
     * A step whose calls were not run — the one a run ran out of steps on — is left out, because a call without its
     * result is a request the providers reject.
     */
    val newMessages: List<Message>
        get() = steps.flatMap { step ->
            when {
                step.toolResults.isNotEmpty() -> listOf(step.response.asMessage(step.agent), Message.Tool(step.toolResults))
                step.reminder != null -> listOf(step.response.asMessage(step.agent), step.reminder)
                step.response.toolCalls.any { !it.providerExecuted } -> emptyList()
                else -> listOf(step.response.asMessage(step.agent))
            }
        }

    /** The usage of every step, added up. It keeps no raw usage, which belongs to a single call. */
    val usage get() = steps.fold(Usage.Unknown) { total, step -> total + step.response.usage }

    /**
     * The estimated cost of every step, added up. Null when a step has none, since a partial sum would pass for
     * the whole.
     */
    val estimatedCost: CostEstimate?
        get() {
            val costs = steps.map { it.response.info.estimatedCost ?: return null }
            return costs.reduce(CostEstimate::plus)
        }

    /** What each call to the model said, and what the run noticed on its own, step by step. */
    val warnings get() = steps.flatMap { it.response.warnings + it.warnings }

    val toolFailures get() = steps.flatMap { it.toolFailures }
}
