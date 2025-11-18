package dev.botta.trantor.console

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.*
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.serviceProvider.ServiceRegistry

abstract class BaseConsoleAppBuilder<T: BaseConsoleApp>(appName: String? = null, environmentName: String? = null) {
    val config = ConfigManager()
    val services = ServiceRegistry(config)
    val environment: AppEnvironment

    init {
        if (environmentName != null) config.addMemoryCollection("environment" to environmentName)
        environment = createAppEnvironment(appName)
        services.addSingleton<AppEnvironment>(environment)

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
        services.addGsonSerializer()
    }

    abstract fun build(): T
}
