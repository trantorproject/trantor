package dev.botta.trantor.events

interface AppEventPublisher {
    fun publish(event: Event)
    fun publish(events: List<Event>)
}
