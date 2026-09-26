package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.defaultHttpClient
import dev.botta.trantor.web.client.HttpClient

/** Name of the provider, shared by the model and its mappers. */
internal const val ANTHROPIC_PROVIDER = "anthropic"

/** Builds Anthropic models for the registry. Every model it builds shares this config and this http client. */
class AnthropicProvider(
    private val config: AnthropicConfig = AnthropicConfig(),
    private val catalog: ModelCatalog = ModelCatalog().addAnthropicModels(),
    private val httpClient: HttpClient = defaultHttpClient,
): AIProvider {
    override val name = ANTHROPIC_PROVIDER

    override fun chatModel(modelId: String) = AnthropicChatModel(modelId, config, httpClient, catalog)
}
