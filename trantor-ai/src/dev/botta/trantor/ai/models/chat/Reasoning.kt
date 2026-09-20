package dev.botta.trantor.ai.models.chat

/**
 * How much the model should think, and whether it should tell what it thought.
 *
 * Providers take either an effort level or a token budget, so both forms exist and each adapter warns when it gets
 * the one it doesn't understand. The reasoning itself is never returned in full: what comes back is a summary, and
 * only if it was asked for.
 */
class Reasoning private constructor(
    val effort: ReasoningEfforts? = null,
    val budgetTokens: Int? = null,
    val summary: ReasoningSummaries = ReasoningSummaries.None,
) {
    override fun equals(other: Any?) = other is Reasoning &&
        other.effort == effort && other.budgetTokens == budgetTokens && other.summary == summary

    override fun hashCode() = 31 * (31 * (effort?.hashCode() ?: 0) + (budgetTokens ?: 0)) + summary.hashCode()

    override fun toString() = when {
        budgetTokens != null -> "Reasoning(budget=$budgetTokens, summary=$summary)"
        effort != null -> "Reasoning($effort, summary=$summary)"
        else -> "Reasoning(Off)"
    }

    companion object {
        val Off = Reasoning()

        fun effort(effort: ReasoningEfforts, summary: ReasoningSummaries = ReasoningSummaries.None) =
            Reasoning(effort = effort, summary = summary)

        fun budget(tokens: Int, summary: ReasoningSummaries = ReasoningSummaries.None) =
            Reasoning(budgetTokens = tokens, summary = summary)
    }
}

enum class ReasoningEfforts { Minimal, Low, Medium, High }

/** Whether to ask for a summary of the reasoning, and how detailed. */
enum class ReasoningSummaries { None, Auto, Detailed }
