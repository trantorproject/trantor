package dev.botta.trantor.core

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
    }

    fun build(): Application {
        val host = hostBuilder.build()
        val application = Application(host)
        services.addSingleton<Host> { application }
        return application
    }
}
