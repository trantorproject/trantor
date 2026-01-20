package dev.botta.trantor.events.eventbus

import dev.botta.trantor.events.Event
import dev.botta.trantor.events.EventHandler

interface EventBus {
    fun publish(event: Event)
    fun publish(events: List<Event>)
    fun subscribe(handler: EventHandler)
    fun start()
    fun stop()
}
