package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event
import kotlin.reflect.KClass

interface EventHandler {
    val eventTypes: List<KClass<*>>

    fun on(event: Event)
}
