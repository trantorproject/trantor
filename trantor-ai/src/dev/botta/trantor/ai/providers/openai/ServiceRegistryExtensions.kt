package dev.botta.trantor.ai.providers.openai

import dev.botta.env.Env
import dev.botta.trantor.ai.addAI
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceRegistry

/**
 * Registers OpenAI as a provider, so that `openai/<model>` and any alias pointing at it can be resolved.
 *
 * Without a [config] it is read from the `ai.providers.openai` section, and the api key falls back to the
 * `OPENAI_API_KEY` environment variable, which is where it belongs: a key does not go in a settings file.
 */
fun ServiceRegistry.addOpenAI(config: OpenAIConfig? = null) = apply {
    addAI()
    addSingleton<AIProvider> { OpenAIProvider(config ?: openAIConfigOf(it.config)) }
}

internal fun openAIConfigOf(config: Config) = OpenAIConfig(
    apiKey = config["$SECTION.apiKey"] ?: Env.getOrThrow("OPENAI_API_KEY"),
    baseUrl = config["$SECTION.baseUrl"] ?: OpenAIConfig.DEFAULT_BASE_URL,
    organization = config["$SECTION.organization"],
    project = config["$SECTION.project"],
    store = config["$SECTION.store"]?.toBooleanStrictOrNull(),
)

private const val SECTION = "ai.providers.openai"
