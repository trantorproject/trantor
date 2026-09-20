package dev.botta.trantor.ai.models.chat

/** How much the model should think. Providers take either an effort level or a token budget. */
class Reasoning private constructor(val effort: ReasoningEfforts? = null, val budgetTokens: Int? = null) {
    override fun equals(other: Any?) = other is Reasoning && other.effort == effort && other.budgetTokens == budgetTokens

    override fun hashCode() = 31 * (effort?.hashCode() ?: 0) + (budgetTokens ?: 0)

    override fun toString() = if (budgetTokens != null) "Reasoning(budget=$budgetTokens)" else "Reasoning($effort)"

    companion object {
        val Off = Reasoning()

        fun effort(effort: ReasoningEfforts) = Reasoning(effort = effort)

        fun budget(tokens: Int) = Reasoning(budgetTokens = tokens)
    }
}

enum class ReasoningEfforts { Minimal, Low, Medium, High }
