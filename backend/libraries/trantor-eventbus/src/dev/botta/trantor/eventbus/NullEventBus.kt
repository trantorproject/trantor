package dev.botta.trantor.eventbus

import dev.botta.trantor.core.events.Event
import dev.botta.trantor.core.events.EventHandler

class NullEventBus: EventBus {
    override fun publish(event: Event) {
    }

    override fun publish(events: List<Event>) {
    }

    override fun subscribe(handler: EventHandler) {
    }

    override fun start() {
    }

    override fun stop() {
    }
}
