package dev.botta.trantor.primitives.events

import kotlin.reflect.KClass

interface EventDispatcher {
    fun publish(event: Event)
    fun subscribe(handler: EventHandler)
    fun <T: Event> on(eventType: KClass<Event>, listener: (e: T) -> Unit)
}
