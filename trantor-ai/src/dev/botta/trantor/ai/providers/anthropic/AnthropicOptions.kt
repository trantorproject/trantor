package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.providers.ProviderOption

/**
 * Options of the Anthropic Messages API that no other provider has, so they don't belong in `ChatSettings`.
 *
 * Everything here **wins over the equivalent setting**, and is sent as it was written without asking the table of
 * [ModelCapabilities] whether the model takes it. That is the point: the plain api aims at working on every model,
 * and this is the way out for somebody who knows their model and wants exactly what they asked for.
 *
 * What is missing here goes with `RawOptions("anthropic", ...)` until it gets typed.
 */
data class AnthropicOptions(
    /**
     * How much of everything the answer costs: text, tool calls and thinking. It is the Anthropic scale, with the
     * two levels above the ones `Reasoning` has. Setting it stops `ChatSettings.reasoning` from choosing a level.
     */
    val effort: AnthropicEfforts? = null,
    /** Whether the model thinks, and how. Setting it stops `ChatSettings.reasoning` from choosing. */
    val thinking: AnthropicThinking? = null,
    /** Overrides `AnthropicConfig.cache` for this call. */
    val cache: AnthropicCaches? = null,
    /** Goes as `metadata.user_id`, for abuse detection. Not an id that identifies a person. */
    val userId: String? = null,
    /** Whether the call can be served by spare capacity, which is cheaper and slower. */
    val serviceTier: ServiceTiers? = null,
): ProviderOption {
    override val provider = ANTHROPIC_PROVIDER
}

/** The five levels Anthropic takes. `Reasoning` only reaches [High]; the two above it are asked for here. */
enum class AnthropicEfforts { Low, Medium, High, XHigh, Max }

sealed interface AnthropicThinking {
    /** No thinking blocks. Some models refuse this above a high effort. */
    data object Off: AnthropicThinking

    /** The model decides whether to think and for how long, steered by the effort. Newer models only. */
    data class Adaptive(val summary: Boolean = true): AnthropicThinking

    /** A fixed budget in tokens, which models from Claude 4.7 on reject. Minimum 1024. */
    data class Budget(val tokens: Int, val summary: Boolean = true): AnthropicThinking
}

/**
 * Prompt caching. Unlike OpenAI, which caches on its own, Anthropic only caches what was marked.
 *
 * [Automatic] is the top-level mark: Anthropic puts the cut on the last cacheable block and moves it forward as
 * the conversation grows, which is the whole of what most applications need. Below the model's minimum — a
 * thousand-odd tokens — nothing is cached and nothing fails either, so leaving it on is never wrong.
 *
 * For a cut somewhere precise, put `cache_control` in the `ProviderMetadata` of the part it goes after. There are
 * four marks per request, and the automatic one takes one of them.
 */
enum class AnthropicCaches {
    Off,
    Automatic,
    /** The same, kept for an hour instead of five minutes. Writing costs more; worth it for a long prefix. */
    AutomaticForAnHour,
}

enum class ServiceTiers { Auto, StandardOnly }
