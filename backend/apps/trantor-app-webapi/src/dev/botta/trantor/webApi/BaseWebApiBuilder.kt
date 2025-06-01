package dev.botta.trantor.webApi

import dev.botta.trantor.config.*
import dev.botta.trantor.config.providers.*
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.serviceProvider.ServiceRegistry
import dev.botta.trantor.web.server.HttpServerConfig

abstract class BaseWebApiBuilder<T: BaseWebApi>(appName: String? = null, environmentName: String? = null) {
    val config = ConfigManager()
    val services = ServiceRegistry()
    val environment: AppEnvironment

    init {
        if (environmentName != null) config.addMemoryCollection("environment" to environmentName)
        environment = createAppEnvironment(appName)
        services.addSingleton<AppEnvironment>(environment)
        services.addSingleton<Config>(config)

        config.addJsonResource("settings.json")
        config.addJsonResource("settings.${environment.environmentName.lowercase()}.json")
        config.addJsonResource("settings.local.json")
        config.addEnvironmentVariables()
        config.addEnvironmentVariables("TRANTOR_")

        addDefaultServices()
    }

    private fun createAppEnvironment(appName: String?): AppEnvironment {
        val environmentName = config["environment"] ?: "PRODUCTION"
        val resolvedAppName = config["appName"] ?: appName ?: "Unnamed App"
        return AppEnvironment(environmentName, resolvedAppName)
    }

    private fun addDefaultServices() {
        // Add Metrics
        services.addConfig<HttpServerConfig>("httpServer")
        services.addGsonSerializer()
    }

    abstract fun build(): T
}
