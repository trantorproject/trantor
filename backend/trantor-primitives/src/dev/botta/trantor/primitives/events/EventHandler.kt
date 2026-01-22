package dev.botta.trantor.primitives.events

import kotlin.reflect.KClass

interface EventHandler {
    val eventTypes: List<KClass<*>>

    fun on(event: Event)
}
