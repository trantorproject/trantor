package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event

interface EventBus {
    suspend fun publish(event: Event)
    suspend fun publish(events: List<Event>)
    fun subscribe(handler: EventHandler)
    fun start()
    fun stop()
}
