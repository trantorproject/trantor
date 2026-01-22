package dev.botta.trantor.core.events

interface AppEventPublisher {
    fun publish(event: Event)
    fun publish(events: List<Event>)
}
