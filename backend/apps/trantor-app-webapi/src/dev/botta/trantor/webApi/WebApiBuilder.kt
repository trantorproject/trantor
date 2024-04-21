package dev.botta.trantor.webApi

import dev.botta.trantor.config.*
import dev.botta.trantor.config.providers.addEnvironmentVariables
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.serviceProvider.*

class WebApiBuilder(appName: String? = null) {
    val config = ConfigManager()
    val services = ServiceRegistry()
    val environment: AppEnvironment

    init {
//        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true")
        config.addEnvironmentVariables()
        config.addEnvironmentVariables("TRANTOR_")

        environment = createAppEnvironment(appName)
        services.addSingleton<AppEnvironment>(environment)
        services.addSingleton<Config>(config)

        //config.addJsonResource("appConfig.json")
        //config.addJsonResource("appConfig.${env}.json")

        addDefaultServices()
    }

    private fun createAppEnvironment(appName: String?): AppEnvironment {
        val env = config["environment"] ?: "PRODUCTION"
        val resolvedAppName = config["appName"] ?: appName ?: "MyApp"
        return AppEnvironment(env, resolvedAppName)
    }

    private fun addDefaultServices() {
        // Add Logger
        // Add Metrics
        services.addGsonSerializer()
    }

    fun build(): WebApi {
        val provider = DefaultServiceProvider(services)
        return WebApi(config, provider)
    }
}
