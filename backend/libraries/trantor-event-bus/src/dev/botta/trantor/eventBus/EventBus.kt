package dev.botta.trantor.eventBus

import dev.botta.trantor.core.events.Event
import dev.botta.trantor.core.events.EventHandler

interface EventBus {
    fun publish(event: Event)
    fun publish(events: List<Event>)
    fun subscribe(handler: EventHandler)
    fun start()
    fun stop()
}
