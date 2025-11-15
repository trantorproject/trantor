package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event

abstract class EventBus {
    abstract suspend fun publish(event: Event)
    abstract suspend fun publish(events: List<Event>)
    abstract fun subscribe(handler: EventHandler)
}
