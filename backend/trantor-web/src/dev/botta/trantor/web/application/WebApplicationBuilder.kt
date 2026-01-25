package dev.botta.trantor.web.application

import dev.botta.trantor.core.application.*
import dev.botta.trantor.hosting.*
import dev.botta.trantor.web.application.requestmapper.ApplicationRequestMapper
import dev.botta.trantor.web.application.routes.WebApplicationExecutor
import dev.botta.trantor.web.server.addHttpServer

class WebApplicationBuilder(private val builderConfig: WebApplicationBuilderConfig): HostBuilder {
    private val applicationBuilder = ApplicationBuilder(
        ApplicationBuilderConfig(
            args = builderConfig.args,
            environmentName = builderConfig.environmentName,
            appName = builderConfig.appName,
            config = builderConfig.config,
            initializeModules = false,
        )
    )
    override val config get() = applicationBuilder.config
    override val services get() = applicationBuilder.services
    override val environment get() = applicationBuilder.environment

    init {
        services.addHttpServer()
        services.addSingleton<ApplicationRequestMapper>()
    }

    fun build(): WebApplication {
        val application = applicationBuilder.build()
        val webApplication = WebApplication(application, application.services.get())
        services.addSingleton<Host> { webApplication }
        services.addSingleton<WebApplication> { webApplication }
        services.addSingleton<WebApplicationExecutor> { webApplication }
        if (builderConfig.initializeModules) {
            application.services.getAll<Module>().forEach { it.initialize(application.services, config) }
        }
        return webApplication
    }
}
