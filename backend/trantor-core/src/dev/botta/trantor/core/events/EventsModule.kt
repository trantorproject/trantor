package dev.botta.trantor.core.events

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import dev.botta.trantor.primitives.events.EventDispatcher

class EventsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingletonIfMissing<EventBus> { InProcessEventBus() }
        services.addSingletonIfMissing<EventDispatcher> { it.create<DefaultEventDispatcher>() }
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
