package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event

class NullEventBus: EventBus() {
    override fun publish(event: Event) {
    }

    override fun publish(events: List<Event>) {
    }

    override fun subscribe(handler: EventHandler) {
    }
}
