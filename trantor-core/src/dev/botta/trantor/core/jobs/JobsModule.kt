package dev.botta.trantor.core.jobs

import dev.botta.trantor.config.*
import dev.botta.trantor.core.jobs.serialization.*
import dev.botta.trantor.core.tx.TransactionsModule
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import dev.botta.trantor.hosting.addModule

class JobsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        // DefaultJobDispatcher takes a TransactionManager, since by default it enqueues after the commit
        services.addModule<TransactionsModule>()

        // IfMissing and not addSingleton: get returns the last registration, so a plain add would replace
        // what the application registered. The two registries are mutable and hold drivers and handlers
        // an application may have loaded already, which would be lost without a word
        services.addSingletonIfMissing<JobQueueRegistry, JobQueueRegistry>()
        services.addSingletonIfMissing<JobHandlerRegistry, JobHandlerRegistry>()
        services.addSingletonIfMissing<JobDispatcher, DefaultJobDispatcher>()
        services.addSingletonIfMissing<JobSerializer, DefaultJobSerializer>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
        val registry = services.get<JobQueueRegistry>()
        registry.loadFromConfig(config)
    }
}
