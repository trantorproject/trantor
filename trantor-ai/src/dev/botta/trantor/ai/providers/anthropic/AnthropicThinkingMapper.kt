package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.chat.Reasoning
import dev.botta.trantor.ai.models.chat.ReasoningEfforts
import dev.botta.trantor.ai.models.chat.ReasoningSummaries

/**
 * The thinking of a request to Anthropic, in whichever shape the model takes, with the notes between tool calls asked
 * for apart where the model writes them. One for each request: afterwards it says whether it asked for the notes
 * ([notes]) and the betas that takes ([betas]).
 */
internal class AnthropicThinkingMapper(
    private val modelId: String,
    private val model: ModelCapabilities?,
    private val supports: ModelSupport,
    private val warnings: MappingWarnings,
) {
    /** Whether the thinking blocks of the answer that carry text are notes between tool calls (see [withNotes]). */
    var notes = false
        private set
    val betas = mutableSetOf<String>()

    /**
     * Thinking, in whichever of the two shapes the model takes. A level is the portable way of asking, so it
     * becomes an effort where there is one and a budget where there isn't; a budget is the exact way of asking,
     * and it has nowhere to go on a model that only takes levels.
     */
    fun of(reasoning: Reasoning?, options: AnthropicOptions?): JsonObject? {
        options?.thinking?.let { return toThinking(it) }

        return withNotes(thinkingFor(reasoning, options))
    }

    /**
     * On a model that writes notes between tool calls ([ModelSupport.progressNotes]), thinking that shows
     * nothing of itself — none asked for, or no summary — asks for the notes apart, with `display: "updates"`:
     * they come back with their text and the thinking blocks stay empty, so the two can be told apart
     * (build-with-claude/thinking, "Progress updates between tool calls", read on 2026-09-30). With no thinking
     * field these models think adaptively already, so asking for it changes nothing of how much they think.
     * `between_tools` brings the notes with their text on its own. A summary is left as it is: its notes cannot
     * be told from the thinking.
     */
    private fun withNotes(thinking: JsonObject?): JsonObject? {
        if (!supports.progressNotes) return thinking

        val type = thinking?.get("type")?.asString()
        if (type == BETWEEN_TOOLS_THINKING) notes = true
        if (thinking != null && (type != ADAPTIVE_THINKING || thinking["display"]?.asString() != OMITTED)) {
            return thinking
        }

        notes = true
        betas.add(NOTES_BETA)

        return Json.obj("type" to ADAPTIVE_THINKING, "display" to NOTES_DISPLAY)
    }

    private fun thinkingFor(reasoning: Reasoning?, options: AnthropicOptions?): JsonObject? {
        if (reasoning == null) return null
        if (reasoning == Reasoning.Off) return thinkingOff(options?.effort)

        reasoning.budgetTokens?.let {
            if (supports.budget) return budgetThinking(it, reasoning.summary)

            warnings.droppedByTheModel("reasoning.budgetTokens")
            return null
        }

        if (reasoning.effort == null) return null
        if (supports.effort) return adaptiveThinking(reasoning.summary)
        if (supports.budget) return budgetThinking(budgetFor(reasoning.effort), reasoning.summary)

        warnings.droppedByTheModel("reasoning")

        return null
    }

    private fun toThinking(thinking: AnthropicThinking) = when (thinking) {
        is AnthropicThinking.Off -> Json.obj("type" to "disabled")
        is AnthropicThinking.Adaptive -> adaptiveThinking(thinking.summary)
        is AnthropicThinking.Budget -> budgetThinking(thinking.tokens, thinking.summary)
        is AnthropicThinking.BetweenTools -> Json.obj("type" to BETWEEN_TOOLS_THINKING)
    }

    /**
     * A model that always thinks answers 400 to thinking disabled: there, thinking less is a lower effort. Sonnet
     * 5.5 has a lowest setting instead, `between_tools`, which does not think before answering and only writes
     * short notes between tool calls; it takes no other field, and Anthropic refuses it above an effort of high
     * (build-with-claude/thinking, read on 2026-09-30).
     */
    private fun thinkingOff(effort: AnthropicEfforts?): JsonObject? {
        if (supports.reasoningOff) return Json.obj("type" to "disabled")

        if (supports.thinkingBetweenTools) {
            if (effort != AnthropicEfforts.XHigh && effort != AnthropicEfforts.Max) {
                return Json.obj("type" to BETWEEN_TOOLS_THINKING)
            }

            val message = "$modelId takes between_tools, its lowest thinking, only at an effort of high or " +
                "below, so Reasoning.Off was not sent with an effort of ${effort.wireName}"
            warnings.add(ModelWarning(message, "reasoning"))
            return null
        }

        warnings.add(
            ModelWarning(
                "$modelId always thinks, so Reasoning.Off was not sent; a lower effort is how it thinks less" +
                    warnings.becauseItIsAGuess,
                "reasoning",
            )
        )

        return null
    }

    private fun adaptiveThinking(summary: ReasoningSummaries) =
        adaptiveThinking(summary != ReasoningSummaries.None)

    private fun adaptiveThinking(summary: Boolean) =
        Json.obj("type" to ADAPTIVE_THINKING, "display" to displayFor(summary))

    private fun budgetThinking(tokens: Int, summary: ReasoningSummaries) =
        budgetThinking(tokens, summary != ReasoningSummaries.None)

    private fun budgetThinking(tokens: Int, summary: Boolean): JsonObject {
        val range = model?.reasoningBudget

        return Json.obj(
            "type" to BUDGET_THINKING,
            // The api refuses anything under a thousand, and a budget over the ceiling leaves no room to answer
            "budget_tokens" to if (range == null) tokens else tokens.coerceIn(range.first, range.last),
        ).also { if (!summary) it["display"] = displayFor(false) }
    }

    /** Omitted still returns the signature, which is what carries the thinking to the next turn. */
    private fun displayFor(summary: Boolean) = if (summary) "summarized" else OMITTED

    /**
     * A share of what the model can give, which is how a level becomes a number of tokens on a model that
     * only takes a budget. The shares are the ones the Vercel AI SDK settled on, and they are a policy rather
     * than a fact: [AnthropicOptions.thinking] is there for whoever wants to say the number.
     */
    private fun budgetFor(effort: ReasoningEfforts) = when (effort) {
        ReasoningEfforts.Minimal -> 0.02
        ReasoningEfforts.Low -> 0.10
        ReasoningEfforts.Medium -> 0.30
        ReasoningEfforts.High -> 0.60
    }.let { ((model?.maxOutputTokens ?: FALLBACK_MAX_TOKENS) * it).toInt() }
}

internal const val BETWEEN_TOOLS_THINKING = "between_tools"
internal const val ADAPTIVE_THINKING = "adaptive"
internal const val OMITTED = "omitted"
internal const val NOTES_DISPLAY = "updates"
internal const val NOTES_BETA = "thinking-display-updates-2026-08-18"

/** Thinking to a number of tokens, which is the shape that refuses a forced tool call. */
internal const val BUDGET_THINKING = "enabled"
