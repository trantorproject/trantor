package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.Part
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.providers.defaultHttpClient
import dev.botta.trantor.web.client.HttpClient

/** Name of the provider, shared by the model and its mappers. */
internal const val OPENAI_PROVIDER = "openai"

/**
 * Marks a [ProviderPart][dev.botta.trantor.ai.models.chat.ProviderPart] that was inside the content of a message
 * rather than an item of its own.
 *
 * The Responses API is the only one of the wire formats with two levels, and a part has to go back to the level it
 * came from or OpenAI gets a message part where it expects an item. That is OpenAI's own business, so it travels
 * in the metadata of the part and not in a field of the shared type.
 */
internal val insideAMessage = ProviderMetadata.of(OPENAI_PROVIDER, Json.obj("inMessage" to true))

internal val Part.wasInsideAMessage get() = metadata[OPENAI_PROVIDER]?.get("inMessage")?.asBoolean() == true

/** Builds OpenAI models for the registry. Every model it builds shares this config and this http client. */
class OpenAIProvider(
    private val config: OpenAIConfig = OpenAIConfig(),
    private val catalog: ModelCatalog = ModelCatalog().addOpenAIModels(),
    private val httpClient: HttpClient = defaultHttpClient,
): AIProvider {
    override val name = OPENAI_PROVIDER

    override fun chatModel(modelId: String) = OpenAIChatModel(modelId, config, httpClient, catalog)
}
