package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.errors.ModelNotFoundError
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.models.middleware.with
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.config.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a model reference into a model.
 *
 * A reference is either `"provider/modelId"` or an alias from the configuration (`fast`, `default`, `cheap`...),
 * which the application defines and can repoint without touching code. An alias can point at another alias.
 *
 * Providers and middlewares add themselves to the registry while the application is being composed, so an alias
 * can name a provider that is not there yet: nothing is resolved until someone asks for a model. Models are built
 * once and kept, and adding a provider or a middleware forgets the ones built so far.
 *
 * Nothing here validates the model id: the registry builds the model and sends it. Whether that model exists is
 * something the provider answers, which is what lets a model that came out today work without a release.
 */
class ModelRegistry {
    private val providers = mutableMapOf<String, AIProvider>()
    private val aliases = mutableMapOf<String, String>()
    private val middlewares = mutableListOf<ChatModelMiddleware>()
    private val chatModels = ConcurrentHashMap<String, ChatModel>()

    @Synchronized
    fun addProvider(provider: AIProvider) = apply {
        if (providers.containsKey(provider.name)) error("Provider ${provider.name} is already registered")

        providers[provider.name] = provider
        chatModels.clear()
    }

    /** Adds a middleware around every model. The first one added is the outermost. */
    @Synchronized
    fun use(middleware: ChatModelMiddleware) = apply {
        middlewares.add(middleware)
        chatModels.clear()
    }

    @Synchronized
    fun addAlias(name: String, reference: String) = apply {
        aliases[name] = reference
        chatModels.clear()
    }

    /**
     * The aliases of the `ai.models` section, as `{"ai": {"models": {"default": "openai/gpt-4.1-mini"}}}`.
     */
    fun loadFromConfig(config: Config, section: String = MODELS_SECTION) = apply {
        if (!config.hasSection(section)) return@apply

        config.getSection(section).getChildren().forEach { child ->
            child.value?.let { addAlias(child.key, it) }
        }
    }

    /** The model the application called `default`. */
    fun chat() = chat(DEFAULT)

    fun chat(reference: String): ChatModel {
        val resolved = resolve(reference)

        return chatModels.computeIfAbsent(resolved) {
            providerOf(it, reference).chatModel(it.substringAfter("/")).with(middlewares.toList())
        }
    }

    private fun providerOf(resolved: String, reference: String): AIProvider {
        val name = resolved.substringBefore("/", missingDelimiterValue = "")

        if (name.isEmpty() || resolved.substringAfter("/").isEmpty()) {
            throw ModelNotFoundError(
                reference,
                "${describe(reference, resolved)} is not a model reference. " +
                    "It should be \"provider/model\"$orAnAlias",
            )
        }

        return providers[name] ?: throw ModelNotFoundError(
            reference,
            "There is no provider called $name. Registered: ${providers.keys.sorted().joinToString()}",
        )
    }

    /** Follows aliases until it reaches something that is not one, so that an alias can point at another. */
    private fun resolve(reference: String): String {
        val seen = mutableSetOf<String>()
        var current = reference

        while (current in aliases) {
            if (!seen.add(current)) {
                throw ModelNotFoundError(reference, "The alias $reference goes in circles: ${seen.joinToString(" -> ")}")
            }
            current = aliases.getValue(current)
        }

        if (current == DEFAULT && DEFAULT !in aliases) {
            throw ModelNotFoundError(reference, "There is no model configured as $DEFAULT$orAnAlias")
        }

        return current
    }

    private fun describe(reference: String, resolved: String) =
        if (reference == resolved) "\"$reference\"" else "The alias \"$reference\" points at \"$resolved\", which"

    private val orAnAlias
        get() = if (aliases.isEmpty()) "" else ", or one of these aliases: ${aliases.keys.sorted().joinToString()}"

    companion object {
        const val DEFAULT = "default"
        const val MODELS_SECTION = "ai.models"
    }
}
