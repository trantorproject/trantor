package dev.botta.trantor.web

import dev.botta.trantor.hosting.*
import dev.botta.trantor.hosting.defaults.DefaultHostBuilder
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.web.server.*

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
        addDefaultServices()
    }

    private fun addDefaultServices() {
        services.addGsonSerializer()
        services.addConfig<HttpServerConfig>("httpServer")
        services.addSingleton { HttpServer(it.getOrDefault<HttpServerConfig> { HttpServerConfig() }) }
        services.addHostedService { it.get<HttpServer>() }
    }

    fun build(): WebApplication {
        val host = hostBuilder.build()
        val webApplication = WebApplication(host)
        services.addSingleton<Host> { webApplication }
        return webApplication
    }
}
