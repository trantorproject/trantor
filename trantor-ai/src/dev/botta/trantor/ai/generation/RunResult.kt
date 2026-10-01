package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.history.Compacted
import dev.botta.trantor.ai.history.Session
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.cost.CostEstimate

/**
 * What a run left: the answer, every step it took to get there and what it all cost.
 *
 * The answer is the last step's. The usage, the estimated cost, the warnings and the tool failures are those of
 * every step, since a run with tools pays for each call to the model and not only for the one that answered. The
 * usage and the cost also count the runs a tool made on its own ([Step.toolRuns]), like an agent that ran as a tool.
 */
data class RunResult(
    val steps: List<Step>,
    /**
     * The conversation the run keeps, compacted once it ended, when it was asked to compact it and did: the one to
     * keep in place of the history it was given. See [dev.botta.trantor.ai.history.Compaction].
     */
    val compacted: Compacted? = null,
    /** Why the compaction it was asked for did not happen, when it failed. */
    val compactionWarnings: List<ModelWarning> = emptyList(),
    /**
     * The calls the conversation left waiting for approval, which the run answered with its decisions before it
     * called the model. Null when it had none.
     */
    val resolved: ResolvedCalls? = null,
) {
    /** The response of the last step, the one that answered. */
    val response get() = steps.last().response

    val text get() = response.text

    val finishReason get() = response.finishReason

    /**
     * The calls that wait for a person to approve them. When there are any, the run ended paused on its last step:
     * the model asked for them, the other calls of that step ran, and the model was not called again.
     */
    val pending: List<PendingCall> get() = steps.last().pending

    /** Whether the run ended waiting for a person to approve some of its calls, which are [pending]. */
    val paused get() = pending.isNotEmpty()

    /**
     * What the run added to the conversation, for the application to keep and send in the next request: every
     * answer of the model and the results of its tools, in order, without what the request already had.
     *
     * A step whose calls were not run — the one a run ran out of steps on — is left out, because a call without its
     * result is a request the providers reject. The step a run paused on is kept with its calls waiting for approval
     * and without their results, which the run that picks it up adds.
     *
     * A run that [resolved] calls starts with their results, which go right after the answer that made them: before
     * the messages it was given, for an application that keeps the conversation itself. [Session] does it on its own.
     */
    val newMessages: List<Message>
        get() = resolved?.let { listOf(Message.Tool(it.results)) }.orEmpty() + stepMessages

    /**
     * [kept], the conversation the run went on from as it is kept, and what the run added, in the order the providers
     * take them: the results of the calls it [resolved] right after the answer that made them, though never before
     * [from], where what cannot change ends, and then its steps.
     */
    internal fun keptAfter(kept: List<Message>, from: Int = 0) = (resolved?.into(kept, from) ?: kept) + stepMessages

    private val stepMessages: List<Message>
        get() = steps.flatMap { step ->
            when {
                step.pending.isNotEmpty() -> listOfNotNull(
                    step.response.asMessage(step.agent),
                    Message.Tool(step.toolResults).takeIf { step.toolResults.isNotEmpty() },
                )
                step.toolResults.isNotEmpty() ->
                    listOf(step.response.asMessage(step.agent), Message.Tool(step.toolResults))
                step.reminder != null -> listOf(step.response.asMessage(step.agent), step.reminder)
                step.response.toolCalls.any { !it.providerExecuted } -> emptyList()
                else -> listOf(step.response.asMessage(step.agent))
            }
        }

    /**
     * The usage of every step, of the runs its tools made, those of the calls it resolved included, and of the call
     * that compacted the conversation, added up. It keeps no raw usage, which belongs to a single call.
     */
    val usage: Usage
        get() {
            val first = resolved?.toolRuns?.values.orEmpty()
                .fold(compacted?.response?.usage ?: Usage.Unknown) { sum, run -> sum + run.usage }

            return steps.fold(first) { total, step ->
                step.toolRuns.values.fold(total + step.response.usage) { sum, run -> sum + run.usage }
            }
        }

    /**
     * The estimated cost of every step, of the runs its tools made and of the call that compacted the conversation,
     * added up. Null when one of them has none, since a partial sum would pass for the whole.
     */
    val estimatedCost: CostEstimate?
        get() {
            val costs = steps.flatMap { step ->
                listOf(step.response.info.estimatedCost ?: return null) +
                    step.toolRuns.values.map { it.estimatedCost ?: return null }
            }
            val compaction = compacted?.let { listOf(it.response.info.estimatedCost ?: return null) }.orEmpty()
            val resolvedRuns = resolved?.toolRuns?.values.orEmpty().map { it.estimatedCost ?: return null }

            return (resolvedRuns + costs + compaction).reduce(CostEstimate::plus)
        }

    /**
     * What each call to the model said, and what the run noticed on its own, step by step, and why the conversation
     * was not compacted if it was not.
     */
    val warnings get() =
        resolved?.warnings.orEmpty() + steps.flatMap { it.response.warnings + it.warnings } + compactionWarnings

    val toolFailures get() = resolved?.failures.orEmpty() + steps.flatMap { it.toolFailures }
}
