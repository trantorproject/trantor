package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.*
import dev.botta.trantor.ai.models.catalog.ValueRange
import dev.botta.trantor.ai.models.chat.ReasoningEfforts.*

/**
 * What each family of OpenAI takes. The split that matters is reasoning against the rest, and it cuts both ways.
 *
 * A reasoning model refuses a temperature that is not its own, with *"Unsupported value: 'temperature' does not
 * support 0.2 with this model. Only the default (1) value is supported."* — the same failure that is open as a bug
 * in LiteLLM, LibreChat and half a dozen others. A model that does not reason refuses `reasoning` instead.
 *
 * The GPT-5.x families sit in the middle and are the reason [ReasoningOff] exists: they reason by default and
 * refuse the sampling settings while they do, but they can be told not to reason at all, and then they take them
 * again. GPT-6 cannot be told that — an effort of `none` is a 400 there — so for it the refusal is flat.
 *
 * Read against the OpenAI model and reasoning guides and against the capability table of the Vercel AI SDK
 * (`openai-language-model-capabilities.ts`), which agree on the split. What neither states per family is the
 * exact ceiling of the older models, so those are left null rather than guessed: nothing in the call path reads
 * them here, since the Responses API does not require `max_output_tokens` the way Anthropic requires `max_tokens`.
 */
internal fun ModelCatalog.addOpenAIModels() = apply {
    add("openai/gpt-6-astra", capabilities = reasoning.copy(maxOutputTokens = 128_000))

    // Reasoning is optional here: with an effort of none they answer without thinking, and take temperature
    add(
        "openai/gpt-5.6-sol",
        "openai/gpt-5.6-terra",
        "openai/gpt-5.6-luna",
        capabilities = reasoningOptional.copy(maxOutputTokens = 128_000),
    )

    add("openai/gpt-5.5", "openai/gpt-5.4", capabilities = reasoningOptional)

    // Minimal exists on the first GPT-5 family and nowhere else
    add(
        "openai/gpt-5",
        "openai/gpt-5-mini",
        "openai/gpt-5-nano",
        capabilities = reasoning.copy(reasoningEfforts = setOf(Minimal, Low, Medium, High)),
    )

    add("openai/o1", "openai/o3", "openai/o3-mini", "openai/o4-mini", capabilities = reasoning)

    add("openai/gpt-4.1", "openai/gpt-4.1-mini", "openai/gpt-4.1-nano", capabilities = sampling)
    add("openai/gpt-4o", "openai/gpt-4o-mini", capabilities = sampling)

    // Structured output starts at the gpt-4o snapshot of august 2024; these are older than that
    add(
        "openai/gpt-4",
        "openai/gpt-4-turbo",
        "openai/gpt-3.5-turbo",
        capabilities = sampling.copy(features = sampling.features - StructuredOutput),
    )

    // A model that came out today is the newest one with something taken away, far more often than not
    setLatest("openai", "openai/gpt-6-astra")

    // What each one costs, which is written per model and not per family
    addOpenAIPrices()
}

/** Reasons on every call and refuses every sampling setting while it does, with no way of turning it off. */
private val reasoning = ModelCapabilities(
    temperature = null,
    topP = null,
    reasoningEfforts = setOf(Low, Medium, High),
    features = setOf(Tools, StructuredOutput, Images, PromptCaching, ForcedToolUse),
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
