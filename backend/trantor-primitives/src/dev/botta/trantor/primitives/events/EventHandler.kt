package dev.botta.trantor.primitives.events

import kotlin.reflect.KClass

interface EventHandler {
    val eventTypes: List<KClass<*>>
    val afterCommit: Boolean? get() = false

    fun on(event: Event)
}
