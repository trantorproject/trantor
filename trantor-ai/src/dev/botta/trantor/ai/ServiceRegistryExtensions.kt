package dev.botta.trantor.ai

import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.providers.openai.addOpenAI
import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry

/**
 * Registers everything an application needs to use models: the [ModelRegistry] and the providers that come with
 * Trantor.
 *
 * Middlewares are named here and nowhere else, in the order they wrap the model, so that reading this call is
 * enough to know what happens around a generation:
 *
 * ```kotlin
 * services.addAI { models, services ->
 *     models.use(RetryMiddleware())
 *     models.use(services.create<CostMiddleware>())
 * }
 * ```
 *
 * An application that does not want the providers of Trantor calls [addModelRegistry] and adds its own.
 */
fun ServiceRegistry.addAI(configuration: ServiceConfiguration<ModelRegistry> = { _, _ -> }) = apply {
    addModelRegistry()
    addOpenAI()
    configure(configuration)
}

/**
 * The registry alone, without any provider. Each provider adds itself with its own extension, before or after this
 * call: nothing is resolved until someone asks for a model.
 */
fun ServiceRegistry.addModelRegistry() = apply {
    if (has<ModelRegistry>()) return@apply

    addSingleton { ModelRegistry().loadFromConfig(it.config) }
}
