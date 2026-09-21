package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.*
import dev.botta.trantor.ai.models.catalog.ValueRange
import dev.botta.trantor.ai.models.chat.ReasoningEfforts.*

/**
 * What each family of Claude takes, which is what keeps a setting from turning into a 400.
 *
 * Three things moved between generations and they are the everyday ones. `temperature`, `top_p` and `top_k` stopped
 * being accepted after Opus 4.6. A thinking budget in tokens gave way to an effort level, and the models from 4.7
 * on refuse a budget. Structured output does not exist before Sonnet 4.5, and neither does asking for a tool
 * strictly, which Anthropic compiles through the same grammar and documents in the same list.
 *
 * It is written by family because that is how it changes: within a generation every model takes the same things,
 * and a dated snapshot is answered by the family it belongs to. Anything not written here is a model the adapter
 * does not protect, which is on purpose — see [ModelCatalog].
 *
 * Anthropic also has an `xhigh` and a `max` above the levels [ReasoningEfforts][dev.botta.trantor.ai.models.chat.ReasoningEfforts]
 * has. They are asked for with [AnthropicOptions.effort], which is not checked against any of this.
 */
internal fun ModelCatalog.addAnthropicModels() = apply {
    add(
        "anthropic/claude-opus-5",
        "anthropic/claude-sonnet-5",
        "anthropic/claude-opus-4-8",
        "anthropic/claude-opus-4-7",
        "anthropic/claude-fable-5",
        "anthropic/claude-mythos-5",
        capabilities = effortOnly,
    )

    // The two that answer 400 to a forced tool call, so the choice of calling one is left to the model
    add(
        "anthropic/claude-fable-5-1",
        "anthropic/claude-mythos-5-1",
        capabilities = effortOnly.copy(features = effortOnly.features - ForcedToolUse),
    )

    // The generation in the middle takes both ways of asking, though the budget is already deprecated there
    add(
        "anthropic/claude-sonnet-4-6",
        "anthropic/claude-opus-4-6",
        capabilities = effortOnly.copy(
            temperature = ValueRange.ZeroToOne,
            topP = ValueRange.ZeroToOne,
            reasoningBudget = MIN_BUDGET..128_000,
        ),
    )

    // The only one of its generation that takes an effort, alongside the budget
    add("anthropic/claude-opus-4-5", capabilities = budgetOnly.copy(reasoningEfforts = setOf(Low, Medium, High)))

    add("anthropic/claude-sonnet-4-5", "anthropic/claude-haiku-4-5", capabilities = budgetOnly)

    add(
        "anthropic/claude-opus-4-1",
        capabilities = budgetOnly.copy(maxOutputTokens = 32_000, reasoningBudget = MIN_BUDGET..32_000),
    )

    // Before structured output existed
    add("anthropic/claude-sonnet-4", capabilities = budgetOnly.copy(features = budgetOnly.features - StructuredOutput))
    add(
        "anthropic/claude-opus-4",
        capabilities = budgetOnly.copy(
            maxOutputTokens = 32_000,
            reasoningBudget = MIN_BUDGET..32_000,
            features = budgetOnly.features - StructuredOutput,
        ),
    )

    // Before thinking existed
    add(
        "anthropic/claude-3-haiku",
        capabilities = ModelCapabilities(
            maxOutputTokens = 4_096,
            temperature = ValueRange.ZeroToOne,
            topP = ValueRange.ZeroToOne,
            features = setOf(Tools, Images, PromptCaching, ForcedToolUse),
        ),
    )

    // A model that came out today is the newest one with something taken away, far more often than not
    setLatest("anthropic", "anthropic/claude-opus-5")
}

/** Effort and no budget: a budget is a 400 here. And no sampling settings at all, which is also a 400. */
private val effortOnly = ModelCapabilities(
    maxOutputTokens = 128_000,
    temperature = null,
    topP = null,
    reasoningEfforts = setOf(Low, Medium, High),
    reasoningBudget = null,
    features = setOf(Tools, StructuredOutput, Images, PromptCaching, ForcedToolUse),
)

/** A budget and no effort, which is how the generation before adaptive thinking asks. */
private val budgetOnly = effortOnly.copy(
    maxOutputTokens = 64_000,
    temperature = ValueRange.ZeroToOne,
    topP = ValueRange.ZeroToOne,
    reasoningEfforts = emptySet(),
    reasoningBudget = MIN_BUDGET..64_000,
)

/** The api refuses anything under this, so a share of the ceiling is raised to it rather than failing the call. */
internal const val MIN_BUDGET = 1_024
