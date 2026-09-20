package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.errors.ModelNotFoundError
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.models.middleware.with
import dev.botta.trantor.ai.providers.AIProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a model reference into a model.
 *
 * A reference is either `"provider/modelId"` or an alias from the configuration (`fast`, `default`, `cheap`...),
 * which the application defines and can repoint without touching code. An alias can point at another alias.
 *
 * Nothing here validates the model id: the registry builds the model and sends it. Whether that model exists is
 * something the provider answers, which is what lets a model that came out today work without a release.
 *
 * Every model it hands out comes wrapped in [middlewares], built once per model and not per call.
 */
class ModelRegistry(
    providers: List<AIProvider>,
    private val aliases: Map<String, String> = emptyMap(),
    private val middlewares: List<ChatModelMiddleware> = emptyList(),
) {
    private val providers = providers.associateBy { it.name }
    private val chatModels = ConcurrentHashMap<String, ChatModel>()

    /** The model the application called `default`. */
    fun chat() = chat(DEFAULT)

    fun chat(reference: String): ChatModel {
        val resolved = resolve(reference)

        return chatModels.computeIfAbsent(resolved) {
            providerOf(it, reference).chatModel(it.substringAfter("/")).with(middlewares)
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
    }
}
