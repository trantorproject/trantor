package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.Event

class DefaultEventPublisher(private val eventBus: EventBus): EventPublisher {
    override fun publish(event: Event) {
        eventBus.publish(event)
    }

    override fun publish(events: List<Event>) {
        eventBus.publish(events)
    }
}
