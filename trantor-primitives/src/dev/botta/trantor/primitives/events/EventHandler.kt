package dev.botta.trantor.primitives.events

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val handlerTypeCache = ConcurrentHashMap<KClass<*>, String>()

interface EventHandler {
    val eventTypes: List<KClass<out Event>>
    val afterCommit: Boolean? get() = true
    val queued: QueuedEventConfig? get() = null
    val handlerType: String get() = this::class.handlerType()

    fun on(event: Event)
}

fun KClass<*>.handlerType(): String =
    handlerTypeCache.getOrPut(this) {
        findAnnotation<EventHandlerType>()?.value
            ?: simpleName
            ?: error("Cannot derive handler type from anonymous class. Use @EventHandlerType.")
    }
