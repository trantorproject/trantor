package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.events.EventHandler

interface EventBus {
    fun publish(event: Event)
    fun publish(events: List<Event>)
    fun subscribe(handler: EventHandler)
    fun start()
    fun stop()
}
