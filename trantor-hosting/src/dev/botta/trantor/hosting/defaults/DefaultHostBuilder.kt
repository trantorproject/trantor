package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.*

class DefaultHostBuilder(private val builderConfig: HostBuilderConfig): HostBuilder {
    override val config = builderConfig.config ?: ConfigManager()
    override val services = ServiceRegistry(config)
    override val environment: HostEnvironment

    init {
        if (!builderConfig.disableDefaults) {
            config.addEnvironmentVariables("TRANTOR__")
        }
        // TODO config.addCommandLineArgsConfig(builderConfig.args)

        addBuilderConfigToConfig()
        environment = createHostEnvironment()
        services.addSingleton<HostEnvironment>(environment)

        if (!builderConfig.disableDefaults) {
            config.addJsonResource("settings.json")
            config.addJsonResource("settings.${environment.environmentName.lowercase()}.json")
            config.addJsonResource("settings.local.json")
            config.addEnvironmentVariables()
            config.addEnvironmentVariables("TRANTOR__")
            // TODO config.addCommandLineArgsConfig(builderConfig.args)
            addDefaultServices()
        }
    }

    private fun addBuilderConfigToConfig() {
        val memoryConfig = mutableMapOf<String, String>()
        builderConfig.environmentName?.let { memoryConfig["env"] = it }
        builderConfig.appName?.let { memoryConfig["appName"] = it }
        if (memoryConfig.isNotEmpty()) {
            config.addMemoryCollection(memoryConfig)
        }
    }

    private fun createHostEnvironment(): HostEnvironment {
        val environmentName = config["env"] ?: "PRODUCTION"
        val appName = config["appName"] ?: "Unnamed App"
        return HostEnvironment(environmentName, appName)
    }

    private fun addDefaultServices() {
        services.addSingletonIfMissing<HostLifetime> { it.create<DefaultHostLifetime>() }
    }

    fun build(): Host {
        val serviceProvider = DefaultServiceProvider(services)
        val lifetime = serviceProvider.get<HostLifetime>() as? DefaultHostLifetime ?: error("HostLifeTime must be DefaultHostLifetime")
        val host = DefaultHost(serviceProvider, config, environment, lifetime)
        services.addSingleton<Host> { host }
        if (services.any { it.serviceType == Module::class.java && it.implementationFactory != null}) {
            error("Modules cannot be registered in the service registry with a factory")
        }

        if (builderConfig.initializeModules) {
            serviceProvider.getAll<Module>()
                .distinctBy { it::class.java }
                .forEach { it.initialize(serviceProvider, config) }
        }
        return host
    }
}
