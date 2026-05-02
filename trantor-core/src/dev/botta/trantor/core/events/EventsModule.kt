package dev.botta.trantor.core.events

import dev.botta.trantor.config.*
import dev.botta.trantor.core.events.serialization.DefaultEventSerializer
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import dev.botta.trantor.primitives.events.EventDispatcher
import dev.botta.trantor.primitives.events.serialization.EventSerializer

class EventsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingletonIfMissing<EventDispatcher, DefaultEventDispatcher>()
        services.addSingletonIfMissing<EventSerializer, DefaultEventSerializer>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
