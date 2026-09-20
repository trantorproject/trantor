package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.addModelCatalog
import dev.botta.trantor.ai.addModelRegistry
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry

/**
 * Adds Anthropic to the registry, so that `anthropic/<model>` and any alias pointing at it can be resolved.
 * [addAI][dev.botta.trantor.ai.addAI] already does this, and calling it again to change the configuration works
 * whichever way round the two calls are made:
 *
 * ```kotlin
 * services.addAnthropic { config, services -> config.betas = listOf("context-1m-2025-08-07") }
 * ```
 */
fun ServiceRegistry.addAnthropic(configuration: ServiceConfiguration<AnthropicConfig> = { _, _ -> }) = apply {
    addAnthropicConfig()
    configure(configuration)

    if (has<AnthropicProvider>()) return@apply

    addModelRegistry()
    addModelCatalog { catalog, _ -> catalog.addAnthropicModels() }
    addSingleton { AnthropicProvider(it.get<AnthropicConfig>(), it.get<ModelCatalog>()) }
    configure<ModelRegistry> { models, services -> models.addProvider(services.get<AnthropicProvider>()) }
}

/** The configuration alone, for an application that builds its Anthropic models by hand. */
fun ServiceRegistry.addAnthropicConfig() = apply {
    if (has<AnthropicConfig>()) return@apply

    addConfig<AnthropicConfig>(SECTION)
}

private const val SECTION = "ai.providers.anthropic"
