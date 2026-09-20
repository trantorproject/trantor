package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.addModelRegistry
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceRegistry

/**
 * Adds OpenAI to the registry, so that `openai/<model>` and any alias pointing at it can be resolved.
 * [addAI][dev.botta.trantor.ai.addAI] already does this; an application calls it directly to give OpenAI a
 * configuration of its own.
 *
 * Without a [config] it is read from the `ai.providers.openai` section, and the api key falls back to the
 * `OPENAI_API_KEY` environment variable, which is where it belongs: a key does not go in a settings file.
 */
fun ServiceRegistry.addOpenAI(config: OpenAIConfig? = null) = apply {
    if (has<OpenAIConfig>()) return@apply

    addModelRegistry()
    addSingleton { config ?: openAIConfigOf(it.config) }
    configure<ModelRegistry> { models, services -> models.addProvider(OpenAIProvider(services.get())) }
}

internal fun openAIConfigOf(config: Config) = OpenAIConfig(
    apiKey = config["$SECTION.apiKey"] ?: OpenAIConfig.apiKeyFromEnvironment(),
    baseUrl = config["$SECTION.baseUrl"] ?: OpenAIConfig.DEFAULT_BASE_URL,
    organization = config["$SECTION.organization"],
    project = config["$SECTION.project"],
    store = config["$SECTION.store"]?.toBooleanStrictOrNull(),
)

private const val SECTION = "ai.providers.openai"
