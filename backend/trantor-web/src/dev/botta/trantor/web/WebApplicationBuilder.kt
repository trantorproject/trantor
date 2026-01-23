package dev.botta.trantor.web

import dev.botta.trantor.core.events.*
import dev.botta.trantor.core.tx.*
import dev.botta.trantor.hosting.*
import dev.botta.trantor.hosting.defaults.DefaultHostBuilder
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.web.server.addHttpServer

class WebApplicationBuilder(private val builderConfig: WebApplicationBuilderConfig): HostBuilder {
    private val hostBuilder = DefaultHostBuilder(
        HostBuilderConfig(
            args = builderConfig.args,
            environmentName = builderConfig.environmentName,
            appName = builderConfig.appName,
            config = builderConfig.config,
        )
    )
    override val config get() = hostBuilder.config
    override val services get() = hostBuilder.services
    override val environment get() = hostBuilder.environment

    init {
        services.addHttpServer()
    }

    private fun addDefaultServices() {
        services.addGsonSerializer()
        services.addSingletonIfMissing<TransactionManager> { NullTransactionManager() }
        services.addSingletonIfMissing<EventBus> { InProcessEventBus() }
        services.addSingletonIfMissing<EventPublisher> { it.create<TransactionAwareEventPublisher>() }
    }

    fun build(): WebApplication {
        addDefaultServices()
        val host = hostBuilder.build()
        val webApplication = WebApplication(host)
        services.addSingleton<Host> { webApplication }
        return webApplication
    }
}
