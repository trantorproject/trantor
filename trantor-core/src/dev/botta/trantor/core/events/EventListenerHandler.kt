package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.*
import kotlin.reflect.KClass

class EventListenerHandler<T: Event>(eventType: KClass<T>, private val listener: EventListener<T>): EventHandler {
    override val eventTypes = listOf(eventType)

    @Suppress("UNCHECKED_CAST")
    override fun on(event: Event) {
        listener(event as T)
    }
}
