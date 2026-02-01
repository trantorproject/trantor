package dev.botta.trantor.core.jobs

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module

class JobsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingleton<JobQueueRegistry> { it.create<JobQueueRegistry>() }
        services.addSingleton<JobHandlerRegistry> { it.create<JobHandlerRegistry>() }
        services.addSingleton<JobDispatcher> { it.create<DefaultJobDispatcher>() }
    }

    override fun initialize(services: ServiceProvider, config: Config) {
        val registry = services.get<JobQueueRegistry>()
        registry.loadFromConfig(config)
    }
}
