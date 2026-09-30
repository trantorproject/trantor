package dev.botta.trantor.ai

import dev.botta.trantor.ai.agents.AgentRunner
import dev.botta.trantor.ai.agents.GlobalAgentHooks
import dev.botta.trantor.ai.agents.GlobalGuardrails
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.providers.anthropic.addAnthropic
import dev.botta.trantor.ai.providers.openai.addOpenAI
import dev.botta.trantor.ai.telemetry.AITelemetrySettings
import dev.botta.trantor.ai.tools.ToolErrorHandlers
import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import io.opentelemetry.api.OpenTelemetry
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.ai.serialization.defaultJsonSerializer

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
 * What a model may learn about a failing tool is named apart, with [addToolErrorHandlers], and the hooks and the
 * guardrails of every run of the agents with [addAgentHooks] and [addGuardrails]. An application that does not want
 * the providers of Trantor calls [addModelRegistry] and adds its own, and one that registered its own [AI] keeps it.
 *
 * When there is an `OpenTelemetry` in the container, registered before or after, the generations and the runs of the
 * agents are traced with it, and [AITelemetrySettings] from the `ai.telemetry` section say whether the traces carry
 * what was said.
 */
fun ServiceRegistry.addAI(configuration: ServiceConfiguration<ModelRegistry> = { _, _ -> }) = apply {
    addModelRegistry()
    addToolErrorHandlers()
    addAgentHooks()
    addGuardrails()
    addOpenAI()
    addAnthropic()
    configure(configuration)
    if (!has<AITelemetrySettings>()) addConfig<AITelemetrySettings>("ai.telemetry")
    addSingletonIfMissing<AI> { DefaultAI(it.get(), it.get(), it.openTelemetry(), it.get(), it.serializer()) }
    addSingletonIfMissing<AgentRunner> {
        AgentRunner(it.get(), it.get(), it.get(), it.get(), it.openTelemetry(), it.get(), it.serializer())
    }
}

/**
 * The hooks every run of the agents calls, before those of the agent and those of the run, in the order they are
 * added:
 *
 * ```kotlin
 * services.addAgentHooks { hooks, services -> hooks.add(services.create<LogTools>()) }
 * ```
 */
fun ServiceRegistry.addAgentHooks(configuration: ServiceConfiguration<GlobalAgentHooks> = { _, _ -> }) = apply {
    if (!has<GlobalAgentHooks>()) addSingleton { GlobalAgentHooks() }

    configure(configuration)
}

/**
 * The guardrails every run of the agents asks, before those of the agent and those of the run, in the order they are
 * added:
 *
 * ```kotlin
 * services.addGuardrails { guardrails, services -> guardrails.input(services.create<OffTopic>()) }
 * ```
 */
fun ServiceRegistry.addGuardrails(configuration: ServiceConfiguration<GlobalGuardrails> = { _, _ -> }) = apply {
    if (!has<GlobalGuardrails>()) addSingleton { GlobalGuardrails() }

    configure(configuration)
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

// Whether it was registered before or after, since it is asked for when AI and the runner are built
private fun ServiceProvider.openTelemetry() = getOrDefault<OpenTelemetry> { OpenTelemetry.noop() }

/** The serializer of the application, which reads and describes the args of its tools; Gson when it has none. */
private fun ServiceProvider.serializer() = getOrDefault<JsonSerializer> { defaultJsonSerializer }
