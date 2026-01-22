package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.Event

interface EventPublisher {
    fun publish(event: Event)
    fun publish(events: List<Event>)
}
