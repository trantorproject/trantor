package dev.botta.trantor.core.events

import dev.botta.trantor.config.*
import dev.botta.trantor.core.events.serialization.DefaultEventSerializer
import dev.botta.trantor.core.jobs.JobsModule
import dev.botta.trantor.core.tx.TransactionsModule
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import dev.botta.trantor.hosting.addModule
import dev.botta.trantor.primitives.events.EventDispatcher
import dev.botta.trantor.primitives.events.serialization.EventSerializer

class EventsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        // DefaultEventDispatcher defers a publish to the commit and queues a job per handler, so it takes a
        // TransactionManager and a JobDispatcher. addModule is idempotent, so composing these again is free
        services.addModule<TransactionsModule>()
        services.addModule<JobsModule>()

        services.addSingletonIfMissing<EventDispatcher, DefaultEventDispatcher>()
        services.addSingletonIfMissing<EventSerializer, DefaultEventSerializer>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
