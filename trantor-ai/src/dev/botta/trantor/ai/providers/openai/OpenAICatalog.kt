package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.*
import dev.botta.trantor.ai.models.catalog.ModelPricing
import dev.botta.trantor.ai.models.catalog.ValueRange
import dev.botta.trantor.ai.models.chat.ReasoningEfforts.*

/**
 * What each OpenAI model takes, and what it costs. The split that matters is reasoning against the rest, and it
 * cuts both ways.
 *
 * A reasoning model refuses a temperature that is not its own, with *"Unsupported value: 'temperature' does not
 * support 0.2 with this model. Only the default (1) value is supported."* — the same failure that is open as a bug
 * in LiteLLM, LibreChat and half a dozen others. A model that does not reason refuses `reasoning` instead.
 *
 * The GPT-5.x families sit in the middle and are the reason [ReasoningOff] exists: they reason by default and
 * refuse the sampling settings while they do, but they can be told not to reason at all, and then they take them
 * again. GPT-6 Astra and GPT-6.1 Sol cannot be told that — an effort of `none` is a 400 there — so for them the
 * refusal is flat.
 * GPT-6 Sol and Luna take an effort of `none`; whether they then take a `temperature` is written nowhere, so they
 * are kept without one, which drops it with a warning instead of risking a 400.
 *
 * Read against the OpenAI model and reasoning guides and against the capability table of the Vercel AI SDK
 * (`openai-language-model-capabilities.ts`), which agree on the split. What neither states per family is the
 * exact ceiling of the older models, so those are left null rather than guessed: nothing in the call path reads
 * them here, since the Responses API does not require `max_output_tokens` the way Anthropic requires `max_tokens`.
 *
 * Each price is the Standard tier of the OpenAI pricing page on 2026-09-21, on 2026-09-23 for GPT-6 Sol and Luna,
 * and on 2026-09-30 for GPT-6.1 Sol (developers.openai.com/api/docs/pricing), for a short context: the GPT-6 and
 * GPT-5.6 families, GPT-5.5 and GPT-5.4 charge more past a long prompt, and for an estimate the short price is the
 * one most calls pay. Only the newest models charge a cache write; on the rest a write is billed as plain input,
 * which is what a missing cacheWrite means.
 */
internal fun ModelCatalog.addOpenAIModels() = apply {
    add(
        "openai/gpt-6-astra",
        gpt6.searchingTools(),
        ModelPricing(input = "10", output = "50", cacheRead = "1", cacheWrite = "12.50"),
    )
    // Takes no effort of none, unlike GPT-6 Sol (developers.openai.com/api/docs/models/gpt-6.1-sol, 2026-09-30)
    add(
        "openai/gpt-6.1-sol",
        gpt6.searchingTools(),
        ModelPricing(input = "2", output = "10", cacheRead = "0.10", cacheWrite = "2.50"),
    )
    add(
        "openai/gpt-6-sol",
        gpt6Optional.searchingTools(),
        ModelPricing(input = "2", output = "10", cacheRead = "0.20", cacheWrite = "2.50"),
    )
    add(
        "openai/gpt-6-luna",
        gpt6Optional.searchingTools(),
        ModelPricing(input = "0.10", output = "0.50", cacheRead = "0.01", cacheWrite = "0.125"),
    )

    add(
        "openai/gpt-5.6-sol",
        gpt56.searchingTools(),
        ModelPricing(input = "4", output = "20", cacheRead = "0.40", cacheWrite = "5"),
    )
    add(
        "openai/gpt-5.6-terra",
        gpt56.searchingTools(),
        ModelPricing(input = "2", output = "12", cacheRead = "0.20", cacheWrite = "2.50"),
    )
    add(
        "openai/gpt-5.6-luna",
        gpt56.searchingTools(),
        ModelPricing(input = "0.20", output = "1.20", cacheRead = "0.02", cacheWrite = "0.25"),
    )

    add(
        "openai/gpt-5.5",
        reasoningOptional.searchingTools(),
        ModelPricing(input = "5", output = "30", cacheRead = "0.50"),
    )
    add(
        "openai/gpt-5.4",
        reasoningOptional.searchingTools(),
        ModelPricing(input = "2.50", output = "15", cacheRead = "0.25"),
    )

    // Deprecated, and out of the API on 2026-12-11 (developers.openai.com/api/docs/deprecations, read on 2026-09-30)
    add("openai/gpt-5", gpt5, ModelPricing(input = "1.25", output = "10", cacheRead = "0.125"))
    add("openai/gpt-5-mini", gpt5, ModelPricing(input = "0.25", output = "2", cacheRead = "0.025"))
    add("openai/gpt-5-nano", gpt5, ModelPricing(input = "0.05", output = "0.40", cacheRead = "0.005"))

    // Deprecated: o3 out of the API on 2026-12-11, and the rest on 2026-10-23 (the same page)
    add("openai/o1", reasoning, ModelPricing(input = "15", output = "60", cacheRead = "7.50"))
    add("openai/o3", reasoning, ModelPricing(input = "2", output = "8", cacheRead = "0.50"))
    add("openai/o3-mini", reasoning, ModelPricing(input = "1.10", output = "4.40", cacheRead = "0.55"))
    add("openai/o4-mini", reasoning, ModelPricing(input = "1.10", output = "4.40", cacheRead = "0.275"))

    add("openai/gpt-4.1", sampling, ModelPricing(input = "2", output = "8", cacheRead = "0.50"))
    add("openai/gpt-4.1-mini", sampling, ModelPricing(input = "0.40", output = "1.60", cacheRead = "0.10"))
    add("openai/gpt-4.1-nano", sampling, ModelPricing(input = "0.10", output = "0.40", cacheRead = "0.025"))
    add("openai/gpt-4o", sampling, ModelPricing(input = "2.50", output = "10", cacheRead = "1.25"))
    add("openai/gpt-4o-mini", sampling, ModelPricing(input = "0.15", output = "0.60", cacheRead = "0.075"))

    // The price list names these two by their snapshots, gpt-4-0613 and gpt-4-turbo-2024-04-09
    add("openai/gpt-4", beforeStructuredOutput, ModelPricing(input = "30", output = "60"))
    add("openai/gpt-4-turbo", beforeStructuredOutput, ModelPricing(input = "10", output = "30"))
    add("openai/gpt-3.5-turbo", beforeStructuredOutput, ModelPricing(input = "0.50", output = "1.50"))

    // Two snapshots that kept their launch price; they take what their model takes
    price("openai/gpt-4o-2024-05-13", ModelPricing(input = "5", output = "15"))
    price("openai/gpt-3.5-turbo-1106", ModelPricing(input = "1", output = "2"))

    // A model that came out today is the newest one with something taken away, far more often than not
    setLatest("openai", "openai/gpt-6-astra")
}

/**
 * Reasons on every call and refuses every sampling setting while it does, with no way of turning it off.
 *
 * Every profile comes from this one, and every OpenAI model takes a system message anywhere in the input: the
 * prompt caching guide puts the ones that change, *"such as user-specific content and timestamps"*, after the
 * stable ones (developers.openai.com/api/docs/guides/prompt-caching, read on 2026-09-23).
 */
private val reasoning = ModelCapabilities(
    temperature = null,
    topP = null,
    reasoningEfforts = setOf(Low, Medium, High),
    features = setOf(Tools, StructuredOutput, Images, PromptCaching, ForcedToolUse, MidConversationSystem),
)

/** Reasons unless it is told not to, and takes the sampling settings when it is. */
private val reasoningOptional = reasoning.copy(
    temperature = ValueRange.ZeroToTwo,
    topP = ValueRange.ZeroToOne,
    features = reasoning.features + ReasoningOff,
)

/** Does not reason at all, so being asked to is what it refuses. */
private val sampling = reasoning.copy(
    temperature = ValueRange.ZeroToTwo,
    topP = ValueRange.ZeroToOne,
    reasoningEfforts = emptySet(),
)

/** GPT-6 Astra cannot be told not to reason: an effort of none is a 400 there. */
private val gpt6 = reasoning.copy(maxOutputTokens = 128_000)

/**
 * GPT-6 Sol and Luna can, with an effort of none (developers.openai.com/api/docs/models, read on 2026-09-23). They
 * keep refusing the sampling settings, since nothing says they take them then.
 */
private val gpt6Optional = gpt6.copy(features = gpt6.features + ReasoningOff)

/** The GPT-5.6 family: reasoning it can be told to skip, and a higher ceiling than the one before. */
private val gpt56 = reasoningOptional.copy(maxOutputTokens = 128_000)

/** Minimal exists on the first GPT-5 family and nowhere else. */
private val gpt5 = reasoning.copy(reasoningEfforts = setOf(Minimal, Low, Medium, High))

/** Structured output starts at the gpt-4o snapshot of august 2024; these are older than that. */
private val beforeStructuredOutput = sampling.copy(features = sampling.features - StructuredOutput)

/**
 * The models that search the tools of a call on OpenAI's side with `tool_search`: *"In the Responses API, only
 * `gpt-5.4` and later models support `tool_search`"* (developers.openai.com/api/docs/guides/tools-tool-search, read on
 * 2026-09-30). Named model by model, since a profile is shared by some that have it and some that do not.
 */
private fun ModelCapabilities.searchingTools() = copy(features = features + ToolSearch)
