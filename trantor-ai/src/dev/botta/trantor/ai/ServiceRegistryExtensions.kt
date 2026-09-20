package dev.botta.trantor.ai

import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceRegistry

/**
 * Registers the [ModelRegistry], with every [AIProvider] and every [ChatModelMiddleware] the application registered
 * and the aliases of the `ai.models` section.
 *
 * Calling it more than once is harmless, and so is the order: nothing is resolved until the first `get`, so a
 * provider registered after this call is still found.
 */
fun ServiceRegistry.addAI() = apply {
    addSingletonIfMissing {
        ModelRegistry(it.getAll<AIProvider>(), aliasesOf(it.config), it.getAll<ChatModelMiddleware>())
    }
}

/**
 * The aliases of the `ai.models` section, as `{"ai": {"models": {"default": "openai/gpt-4.1-mini"}}}`. A name that
 * the application can repoint without touching code.
 */
private fun aliasesOf(config: Config): Map<String, String> {
    if (!config.hasSection(MODELS_SECTION)) return emptyMap()

    return config.getSection(MODELS_SECTION).getChildren()
        .mapNotNull { section -> section.value?.let { section.key to it } }
        .toMap()
}

private const val MODELS_SECTION = "ai.models"
