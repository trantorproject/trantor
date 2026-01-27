package dev.botta.trantor.primitives.events

import kotlin.reflect.KClass

interface EventDispatcher {
    fun publish(event: Event)
    fun publish(events: List<Event>) = events.forEach { publish(it) }
    fun subscribe(handler: EventHandler)
    fun defer(block: () -> Unit)
    fun <T: Event> on(eventType: KClass<T>, listener: EventListener<T>)
}

inline fun <reified T: Event> EventDispatcher.on(noinline listener: EventListener<T>) = on(T::class, listener)

typealias EventListener<T> = (e: T) -> Unit
