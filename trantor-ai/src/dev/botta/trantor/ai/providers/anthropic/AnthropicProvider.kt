package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.web.client.HttpClient

/** Name of the provider, shared by the model and its mappers. */
internal const val ANTHROPIC_PROVIDER = "anthropic"

/** Builds Anthropic models for the registry. Every model it builds shares this config and this http client. */
class AnthropicProvider(
    private val config: AnthropicConfig = AnthropicConfig(),
    private val httpClient: HttpClient? = null,
): AIProvider {
    override val name = ANTHROPIC_PROVIDER

    override fun chatModel(modelId: String) =
        if (httpClient == null) AnthropicChatModel(modelId, config) else AnthropicChatModel(modelId, config, httpClient)
}
