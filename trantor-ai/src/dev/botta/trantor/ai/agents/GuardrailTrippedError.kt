package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.errors.AIError
import dev.botta.trantor.ai.models.chat.ToolCallPart

/**
 * A guardrail stopped the run. It says which one, of what kind and why, and carries what the run left so that
 * nothing spent is lost.
 */
class GuardrailTrippedError(
    /** The name of the guardrail. */
    val guardrail: String,
    val kind: GuardrailKinds,
    val reason: String,
    /** What else the guardrail found, as it returned it in [GuardrailVerdict.Trip]. */
    val details: Any?,
    /** The agent whose guardrail it was: the first one for the input, the one that answered for the output. */
    val agent: Agent,
    /**
     * What the run left: null for an input guardrail, which runs before any step. For a tool guardrail, its last
     * step is the one whose calls did not run, which [AgentRunResult.newMessages] leaves out.
     */
    val result: AgentRunResult?,
    /** The call a tool guardrail stopped the run on. */
    val call: ToolCallPart? = null,
): AIError("The ${kind.label} guardrail $guardrail stopped the run of ${agent.name}: $reason")

/** What a guardrail checks. */
enum class GuardrailKinds(internal val label: String) {
    /** The conversation a run got. */
    Input("input"),

    /** The final answer. */
    Output("output"),

    /** A call to a tool. */
    Tool("tool"),
}
