package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.tools.ToolOutput

/**
 * The handoffs of one step, settled in the order of the calls as they finish. The conversation changes hands once
 * the step is over, so every call of the step still runs with the tools that asked for it.
 *
 * - The first handoff to an agent of the team wins.
 * - A later one in the same step is answered as an error to the model — its tool did run, with its effects — and
 *   leaves a warning, since the model asked for two things at once and only one could happen.
 * - One to an agent outside the team is answered as an error naming the team, so the model can pick again.
 * - Without a team, which is a generation, there is nobody to hand over to: it is left with a warning.
 * - In a step that pauses for approval, it is answered as an error, with a warning: the run that picks the
 *   conversation up goes on with the agent that made the calls that wait, and could not tell it had been handed
 *   over. The model can hand it over again then.
 */
internal class Handoffs(private val team: Set<String>?) {
    /** The agent the conversation goes to after the step. */
    var winner: String? = null
        private set
    private var winnerTool: String? = null
    val warnings = mutableListOf<ModelWarning>()

    fun settle(execution: ToolExecution, paused: Boolean): ToolExecution {
        val to = execution.handoff ?: return execution
        val tool = execution.result.toolName

        if (team == null) {
            warnings.add(
                ModelWarning(
                    "$tool handed the conversation over to $to, but a generation has no agents to hand it to; " +
                        "it was ignored",
                )
            )
            return execution
        }

        if (to !in team) return refused(execution, "There is no agent called $to. The team is: ${team.joinToString()}")

        if (paused) {
            warnings.add(
                ModelWarning(
                    "$tool handed the conversation over to $to in a step that paused for approval, so it was not " +
                        "handed over",
                ),
            )
            return refused(
                execution,
                "The conversation was not handed over to $to, since other calls of this step wait for approval. " +
                    "Hand it over again once they are answered.",
            )
        }

        val first = winner ?: return execution.also {
            winner = to
            winnerTool = tool
        }

        warnings.add(
            ModelWarning(
                "$tool handed the conversation over to $to after $winnerTool had handed it over to $first in " +
                    "the same step; the first one won",
            )
        )
        return refused(
            execution,
            "The conversation was already handed over to $first, so this handoff to $to was ignored",
        )
    }

    private fun refused(execution: ToolExecution, message: String) = ToolExecution(
        execution.result.copy(output = ToolOutput.Text(message), isError = true),
        execution.failure,
        run = execution.run,
    )
}
