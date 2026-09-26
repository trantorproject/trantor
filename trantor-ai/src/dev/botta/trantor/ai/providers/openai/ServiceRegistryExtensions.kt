package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.addModelCatalog
import dev.botta.trantor.ai.addModelRegistry
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.web.client.addHttpClient

/**
 * Adds OpenAI to the registry, so that `openai/<model>` and any alias pointing at it can be resolved.
 * [addAI][dev.botta.trantor.ai.addAI] already does this, and calling it again to change the configuration works
 * whichever way round the two calls are made:
 *
 * ```kotlin
 * services.addOpenAI { config, services -> config.organization = "org-7" }
 * ```
 *
 * Its models call through the [HttpClient] of the application, which this adds when there is none, so they share
 * its connections and are traced along with every other call. The client has to stream, as the one of Trantor
 * does. How long a model may go silent is [OpenAIConfig.readTimeout], not a setting of the client.
 */
fun ServiceRegistry.addOpenAI(configuration: ServiceConfiguration<OpenAIConfig> = { _, _ -> }) = apply {
    addOpenAIConfig()
    configure(configuration)

    if (has<OpenAIProvider>()) return@apply

    addModelRegistry()
    addModelCatalog { catalog, _ -> catalog.addOpenAIModels() }
    if (!has<HttpClient>()) addHttpClient()
    addSingleton { OpenAIProvider(it.get<OpenAIConfig>(), it.get<ModelCatalog>(), it.get<HttpClient>()) }
    configure<ModelRegistry> { models, services -> models.addProvider(services.get<OpenAIProvider>()) }
}

/** The configuration alone, for an application that builds its OpenAI models by hand. */
fun ServiceRegistry.addOpenAIConfig() = apply {
    if (has<OpenAIConfig>()) return@apply

    addConfig<OpenAIConfig>(SECTION)
}

private const val SECTION = "ai.providers.openai"
