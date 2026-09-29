package dev.botta.trantor.core.application

import dev.botta.cqbus.CQBus
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.trantor.core.auth.CurrentIdentity
import dev.botta.trantor.core.cache.CacheModule
import dev.botta.trantor.core.events.EventsModule
import dev.botta.trantor.core.jobs.JobsModule
import dev.botta.trantor.core.tx.TransactionsModule
import dev.botta.trantor.core.validation.ValidationModule
import dev.botta.trantor.hosting.*
import dev.botta.trantor.hosting.defaults.DefaultHostBuilder
import dev.botta.trantor.serialization.gson.addGsonSerializer

class ApplicationBuilder(private val builderConfig: ApplicationBuilderConfig): HostBuilder {
    private val hostBuilder = DefaultHostBuilder(
        HostBuilderConfig(
            args = builderConfig.args,
            environmentName = builderConfig.environmentName,
            appName = builderConfig.appName,
            config = builderConfig.config,
            initializeModules = false,
        )
    )
    override val config get() = hostBuilder.config
    override val services get() = hostBuilder.services
    override val environment get() = hostBuilder.environment

    private fun addDefaultServices() {
        services.addGsonSerializer()
        services.addSingletonIfMissing { CQBus() }
        services.configure<CQBus> { bus, _ ->
            bus.registerContextAwareHandler<CurrentIdentity, Identity> {
                ContextAwareRequestHandler { _, context -> context.identity }
            }
        }
        services.addSingletonIfMissing<ApplicationExecutor> { it.create<DefaultApplicationExecutor>() }
        services.addModule<TransactionsModule>()
        services.addModule<EventsModule>()
        services.addModule<CacheModule>()
        services.addModule<JobsModule>()
        services.addModule<ValidationModule>()
    }

    fun build(): Application {
        addDefaultServices()
        val host = hostBuilder.build()
        val executor = host.services.get<ApplicationExecutor>()
        val application = Application(host, executor)
        services.addSingleton<Host> { application }
        services.addSingleton<Application> { application }
        if (builderConfig.initializeModules) {
            host.services.getAll<Module>()
                .distinctBy { it::class.java }
                .forEach { it.initialize(host.services, config) }
        }
        return application
    }
}
