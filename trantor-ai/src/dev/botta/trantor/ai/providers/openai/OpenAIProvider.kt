package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.web.client.HttpClient

/** Name of the provider, shared by the model and its mappers. */
internal const val OPENAI_PROVIDER = "openai"

/** Builds OpenAI models for the registry. Every model it builds shares this config and this http client. */
class OpenAIProvider(
    private val config: OpenAIConfig = OpenAIConfig(),
    private val httpClient: HttpClient? = null,
): AIProvider {
    override val name = OPENAI_PROVIDER

    override fun chatModel(modelId: String) =
        if (httpClient == null) OpenAIChatModel(modelId, config) else OpenAIChatModel(modelId, config, httpClient)
}
