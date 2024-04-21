package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event

abstract class EventBus {
    abstract fun publish(event: Event)
    abstract fun publish(events: List<Event>)
    abstract fun subscribe(handler: EventHandler)
}
