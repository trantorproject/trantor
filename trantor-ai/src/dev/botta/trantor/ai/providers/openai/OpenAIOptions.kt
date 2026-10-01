package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.providers.ProviderOption

/**
 * Options of the OpenAI Responses API that no other provider has, so they don't belong in `ChatSettings`.
 *
 * What is missing here goes with `RawOptions("openai", ...)` until it gets typed.
 */
data class OpenAIOptions(
    // How the call is scheduled and billed
    val serviceTier: OpenAIServiceTiers? = null,
    // Overrides OpenAIConfig.store for this call only
    val store: Boolean? = null,
    // Steers prompt caching: calls sharing a key are likelier to hit the cache
    val promptCacheKey: String? = null,
    // Identifies the end user for abuse detection, without sending anything that identifies a person
    val safetyIdentifier: String? = null,
    // What to do when the conversation doesn't fit the context window
    val truncation: OpenAITruncations? = null,
    // How long the answer should be
    val verbosity: OpenAIVerbosities? = null,
): ProviderOption {
    override val provider = OPENAI_PROVIDER
}

/** Which capacity of OpenAI serves a call: flex is slower and cheaper, priority faster and dearer. */
enum class OpenAIServiceTiers { Auto, Default, Flex, Priority }

/** What OpenAI does with a conversation that does not fit: drop its start, or fail. */
enum class OpenAITruncations { Auto, Disabled }

/** How long OpenAI is to make its answers. */
enum class OpenAIVerbosities { Low, Medium, High }
