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
 * on refuse a budget. Structured output does not exist before Sonnet 4.5, and neither does asking for a tool
 * strictly, which Anthropic compiles through the same grammar and documents in the same list. And only the newest
 * take a system message in the middle of the conversation.
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
    add("anthropic/claude-opus-5", systemAnywhere, opusPrice)
    add("anthropic/claude-sonnet-5", effortOnly, sonnet5Price)
    add("anthropic/claude-opus-4-8", systemAnywhere, opusPrice)
    add("anthropic/claude-opus-4-7", effortOnly, opusPrice)
    add("anthropic/claude-fable-5", systemAnywhere, fablePrice)
    add("anthropic/claude-mythos-5", systemAnywhere, fablePrice)
    add("anthropic/claude-fable-5-1", withoutForcedToolUse, fable51Price)
    add("anthropic/claude-mythos-5-1", withoutForcedToolUse, fable51Price)

    add("anthropic/claude-opus-4-6", bothWays, opusPrice)
    add("anthropic/claude-sonnet-4-6", bothWays, sonnetPrice)

    // The only one of its generation that takes an effort, alongside the budget
    add("anthropic/claude-opus-4-5", budgetOnly.copy(reasoningEfforts = setOf(Low, Medium, High)), opusPrice)
    add("anthropic/claude-sonnet-4-5", budgetOnly, sonnetPrice)
    add("anthropic/claude-haiku-4-5", budgetOnly, haikuPrice)

    add("anthropic/claude-opus-4-1", earlyOpus, earlyOpusPrice)
    // Before structured output existed
    add("anthropic/claude-opus-4", earlyOpus.copy(features = earlyOpus.features - StructuredOutput), earlyOpusPrice)
    add("anthropic/claude-sonnet-4", budgetOnly.copy(features = budgetOnly.features - StructuredOutput), sonnetPrice)

    // Before thinking existed. Off the price list of Anthropic, and a price nobody publishes is not one to write
    add(
        "anthropic/claude-3-haiku",
        ModelCapabilities(
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

/**
 * The newest ones also take a system message anywhere in the conversation, where Sonnet 5 and Opus 4.7 take it only
 * as the system field (platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages, read on
 * 2026-09-23). Opus 5.5 is on that list too, and not in this catalog yet.
 */
private val systemAnywhere = effortOnly.copy(features = effortOnly.features + MidConversationSystem)

/** Fable 5.1 and Mythos 5.1 answer 400 to a forced tool call, so the choice of calling one is left to the model. */
private val withoutForcedToolUse = systemAnywhere.copy(features = systemAnywhere.features - ForcedToolUse)

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

/** The first Opus of the Claude 4 generation, with half the ceiling of the ones that came after. */
private val earlyOpus = budgetOnly.copy(maxOutputTokens = 32_000, reasoningBudget = MIN_BUDGET..32_000)

/*
 * What each tier costs, in dollars per million tokens, as the Anthropic pricing page had it on 2026-09-21
 * (platform.claude.com/docs/en/about-claude/pricing). The cache write is the five-minute one: a cache kept an hour
 * costs more to write, and for an estimate the difference is not worth a second price. Cache reads are a tenth of
 * the input everywhere but on Fable 5.1 and Mythos 5.1, where they are a fortieth.
 */
private val fable51Price = ModelPricing(input = "10", output = "50", cacheRead = "0.25", cacheWrite = "12.50")
private val fablePrice = ModelPricing(input = "10", output = "50", cacheRead = "1", cacheWrite = "12.50")
private val opusPrice = ModelPricing(input = "5", output = "25", cacheRead = "0.50", cacheWrite = "6.25")
private val earlyOpusPrice = ModelPricing(input = "15", output = "75", cacheRead = "1.50", cacheWrite = "18.75")
private val sonnet5Price = ModelPricing(input = "2", output = "10", cacheRead = "0.20", cacheWrite = "2.50")
private val sonnetPrice = ModelPricing(input = "3", output = "15", cacheRead = "0.30", cacheWrite = "3.75")
private val haikuPrice = ModelPricing(input = "1", output = "5", cacheRead = "0.10", cacheWrite = "1.25")

/** The api refuses anything under this, so a share of the ceiling is raised to it rather than failing the call. */
internal const val MIN_BUDGET = 1_024
