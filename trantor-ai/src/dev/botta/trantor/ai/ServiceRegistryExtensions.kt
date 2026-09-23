package dev.botta.trantor.ai

import dev.botta.trantor.ai.agents.AgentRunner
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.providers.anthropic.addAnthropic
import dev.botta.trantor.ai.providers.openai.addOpenAI
import dev.botta.trantor.ai.tools.ToolErrorHandlers
import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry

/**
 * Registers everything an application needs to use models: [AI], the [AgentRunner], the [ModelRegistry] and the
 * providers that come with Trantor.
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
 * What a model may learn about a failing tool is named apart, with [addToolErrorHandlers]. An application that does
 * not want the providers of Trantor calls [addModelRegistry] and adds its own, and one that registered its own [AI]
 * keeps it.
 */
fun ServiceRegistry.addAI(configuration: ServiceConfiguration<ModelRegistry> = { _, _ -> }) = apply {
    addModelRegistry()
    addToolErrorHandlers()
    addOpenAI()
    addAnthropic()
    configure(configuration)
    addSingletonIfMissing<AI> { DefaultAI(it.get(), it.get()) }
    addSingletonIfMissing<AgentRunner> { AgentRunner(it.get(), it.get()) }
}

/**
 * The handlers that turn an exception of a tool into something the model may read, asked in the order they are
 * added. Without them the model gets "Tool execution failed" and the exception stays with the application:
 *
 * ```kotlin
 * services.addToolErrorHandlers { handlers, _ ->
 *     handlers.add { error, _ -> if (error is NotFoundError) "It does not exist" else null }
 * }
 * ```
 */
fun ServiceRegistry.addToolErrorHandlers(configuration: ServiceConfiguration<ToolErrorHandlers> = { _, _ -> }) =
    apply {
        if (!has<ToolErrorHandlers>()) addSingleton { ToolErrorHandlers() }

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

/**
 * The catalog of what each model takes, which is what lets an adapter drop a setting the model would refuse
 * instead of losing the call. Each provider adds its own models; an application adds or replaces whatever it
 * wants, so a model that came out today works without a release:
 *
 * ```kotlin
 * services.addModelCatalog { catalog, _ ->
 *     catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5") { copy(maxOutputTokens = 256_000) }
 * }
 * ```
 */
fun ServiceRegistry.addModelCatalog(configuration: ServiceConfiguration<ModelCatalog> = { _, _ -> }) = apply {
    if (!has<ModelCatalog>()) addSingleton { ModelCatalog() }

    configure(configuration)
}
