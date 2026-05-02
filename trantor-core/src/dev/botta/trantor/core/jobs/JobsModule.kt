package dev.botta.trantor.core.jobs

import dev.botta.trantor.config.*
import dev.botta.trantor.core.jobs.serialization.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module

class JobsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingleton<JobQueueRegistry, JobQueueRegistry>()
        services.addSingleton<JobHandlerRegistry, JobHandlerRegistry>()
        services.addSingleton<JobDispatcher, DefaultJobDispatcher>()
        services.addSingleton<JobSerializer, DefaultJobSerializer>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
        val registry = services.get<JobQueueRegistry>()
        registry.loadFromConfig(config)
    }
}
