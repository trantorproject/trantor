package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.*
import dev.botta.trantor.ai.models.catalog.ModelPricing
import dev.botta.trantor.ai.models.catalog.ValueRange
import dev.botta.trantor.ai.models.chat.ReasoningEfforts.*

/**
 * What each Claude takes, which is what keeps a setting from turning into a 400, and what it costs.
 *
 * Three things moved between generations and they are the everyday ones. `temperature`, `top_p` and `top_k` stopped
 * being accepted after Opus 4.6. A thinking budget in tokens gave way to an effort level, and the models from 4.7
 * on refuse a budget. And only the newest take a system message in the middle of the conversation.
 *
 * Only the models Anthropic still serves are here: a retired one answers 404, so describing it would only make it
 * look usable. Opus 4.1, Opus 4, Sonnet 4 and Haiku 3 left on 2026-09-30, as retired on the model deprecations page
 * (platform.claude.com/docs/en/about-claude/model-deprecations); every model left takes structured output.
 *
 * One line per model, naming a profile of what it takes and a tier of what it costs: within a generation every
 * model takes the same things, and within a tier every model costs the same. A dated snapshot is answered by the
 * model it belongs to. Anything not written here is a model the adapter does not protect, which is on purpose —
 * see [ModelCatalog].
 *
 * Anthropic also has an `xhigh` and a `max` above the levels
 * [ReasoningEfforts][dev.botta.trantor.ai.models.chat.ReasoningEfforts] has. They are asked for with
 * [AnthropicOptions.effort], which is not checked against any of this.
 */
internal fun ModelCatalog.addAnthropicModels() = apply {
    add("anthropic/claude-opus-5-5", boundThinking.searchingTools(), opus55Price)
    add("anthropic/claude-opus-5", systemAnywhere.searchingTools(), opusPrice)
    // Costs what Sonnet 5 does (models/sonnet-5-5/whats-new, read on 2026-09-30)
    add("anthropic/claude-sonnet-5-5", thinkingBetweenTools.searchingTools(), sonnet5Price)
    add("anthropic/claude-sonnet-5", effortOnly, sonnet5Price)
    add("anthropic/claude-opus-4-8", systemAnywhere.searchingTools(), opusPrice)
    add("anthropic/claude-opus-4-7", effortOnly.searchingTools(), opusPrice)
    add("anthropic/claude-fable-5", alwaysThinking.searchingTools(), fablePrice)
    add("anthropic/claude-mythos-5", alwaysThinking.searchingTools(), fablePrice)
    add("anthropic/claude-fable-5-1", boundThinking.searchingTools(), fable51Price)
    add("anthropic/claude-mythos-5-1", withoutForcedToolUse.searchingTools(), fable51Price)

    add("anthropic/claude-opus-4-6", bothWays.searchingTools(), opusPrice)
    add("anthropic/claude-sonnet-4-6", bothWays.searchingTools(), sonnetPrice)

    // The only one of its generation that takes an effort, alongside the budget
    add(
        "anthropic/claude-opus-4-5",
        budgetOnly.copy(reasoningEfforts = setOf(Low, Medium, High)).searchingTools(),
        opusPrice,
    )
    add("anthropic/claude-sonnet-4-5", budgetOnly.searchingTools(), sonnetPrice)
    add("anthropic/claude-haiku-4-5", budgetOnly.searchingTools(), haikuPrice)

    // A model that came out today is the newest one with something taken away, far more often than not
    setLatest("anthropic", "anthropic/claude-opus-5-5")
}

/**
 * The models that search the tools of a call on Anthropic's side, as "Model compatibility" of its tool search lists
 * them (read on 2026-09-30): every Claude from Haiku, Sonnet and Opus 4.5 on, but not Sonnet 5, which the list leaves
 * out. Named model by model, since a profile is shared by some that have it and some that do not.
 */
private fun ModelCapabilities.searchingTools() = copy(features = features + ToolSearch)

/**
 * Effort and no budget: a budget is a 400 here. And no sampling settings at all, which is also a 400. Thinking can
 * still be turned off, which on Opus 5 holds up to an effort of `high`.
 */
private val effortOnly = ModelCapabilities(
    maxOutputTokens = 128_000,
    temperature = null,
    topP = null,
    reasoningEfforts = setOf(Low, Medium, High),
    reasoningBudget = null,
    features = setOf(Tools, StructuredOutput, Images, PromptCaching, ForcedToolUse, ReasoningOff),
)

/**
 * The newest ones also take a system message anywhere in the conversation, where Sonnet 5 and Opus 4.7 take it only
 * as the system field (platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages, read on
 * 2026-09-23).
 */
private val systemAnywhere = effortOnly.copy(features = effortOnly.features + MidConversationSystem)

/**
 * Fable, Mythos and Opus 5.5 always think, and answer 400 to thinking disabled; a lower effort is how they think less
 * (platform.claude.com/docs/en/build-with-claude/thinking, read on 2026-09-23). So does Sonnet 5.5, whose lowest
 * setting is `between_tools`, which thinks between tool calls but not before the first.
 */
private val alwaysThinking = systemAnywhere.copy(features = systemAnywhere.features - ReasoningOff)

/**
 * Fable 5.1, Mythos 5.1, Opus 5.5 and Sonnet 5.5 also answer 400 to a forced tool call, so the choice of calling one
 * is left to the model.
 */
private val withoutForcedToolUse = alwaysThinking.copy(features = alwaysThinking.features - ForcedToolUse)

/**
 * Opus 5.5, Sonnet 5.5 and Fable 5.1 also tie each thinking block to everything before it, and answer 400 when that
 * changed; Mythos 5.1 does not (platform.claude.com/docs/en/build-with-claude/preserved-thinking, read on 2026-09-23).
 */
private val boundThinking = withoutForcedToolUse.copy(features = withoutForcedToolUse.features + BoundThinking)

/**
 * Sonnet 5.5 takes what Opus 5.5 takes, and one thing more: thinking only between tool calls, which is its lowest
 * setting (build-with-claude/thinking, read on 2026-09-30).
 */
private val thinkingBetweenTools = boundThinking.copy(features = boundThinking.features + ThinkingBetweenTools)

/** The generation in the middle takes both ways of asking, though the budget is already deprecated there. */
private val bothWays = effortOnly.copy(
    temperature = ValueRange.ZeroToOne,
    topP = ValueRange.ZeroToOne,
    reasoningBudget = MIN_BUDGET..128_000,
)

/** A budget and no effort, which is how the generation before adaptive thinking asks. */
private val budgetOnly = effortOnly.copy(
    maxOutputTokens = 64_000,
    temperature = ValueRange.ZeroToOne,
    topP = ValueRange.ZeroToOne,
    reasoningEfforts = emptySet(),
    reasoningBudget = MIN_BUDGET..64_000,
)

/*
 * What each tier costs, in dollars per million tokens, as the Anthropic pricing page had it on 2026-09-21, and on
 * 2026-09-23 for Opus 5.5 (platform.claude.com/docs/en/about-claude/pricing). The cache write is the five-minute one:
 * a cache kept an hour costs more to write, and for an estimate the difference is not worth a second price. Cache
 * reads are a tenth of the input everywhere but on Fable 5.1 and Mythos 5.1, where they are a fortieth, and on
 * Opus 5.5, where they are a twentieth.
 */
private val fable51Price = ModelPricing(input = "10", output = "50", cacheRead = "0.25", cacheWrite = "12.50")
private val fablePrice = ModelPricing(input = "10", output = "50", cacheRead = "1", cacheWrite = "12.50")
private val opus55Price = ModelPricing(input = "4", output = "20", cacheRead = "0.20", cacheWrite = "5")
private val opusPrice = ModelPricing(input = "5", output = "25", cacheRead = "0.50", cacheWrite = "6.25")
private val sonnet5Price = ModelPricing(input = "2", output = "10", cacheRead = "0.20", cacheWrite = "2.50")
private val sonnetPrice = ModelPricing(input = "3", output = "15", cacheRead = "0.30", cacheWrite = "3.75")
private val haikuPrice = ModelPricing(input = "1", output = "5", cacheRead = "0.10", cacheWrite = "1.25")

/** The api refuses anything under this, so a share of the ceiling is raised to it rather than failing the call. */
internal const val MIN_BUDGET = 1_024
