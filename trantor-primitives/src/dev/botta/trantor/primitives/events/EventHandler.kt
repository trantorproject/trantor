package dev.botta.trantor.primitives.events

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val handlerTypeCache = ConcurrentHashMap<KClass<*>, String>()

/**
 * Reacts to the events of [eventTypes], and to those of their subclasses.
 *
 * In line, which is the default, a handler that throws is logged and never tried again. One whose effect has to
 * happen is a [queued] one, see [QueuedEventHandler].
 */
interface EventHandler {
    val eventTypes: List<KClass<out Event>>

    /** Whether it waits for the commit of the transaction in course, so it never sees a change that was undone. */
    val afterCommit: Boolean? get() = true

    /** The queue it runs on as a job, or `null` to run in line. A queued handler that throws is retried. */
    val queued: QueuedEventConfig? get() = null

    /** The name its jobs find it by. It is the class name, so renaming a queued handler needs [EventHandlerType]. */
    val handlerType: String get() = this::class.handlerType()

    fun on(event: Event)
}

fun KClass<*>.handlerType(): String =
    handlerTypeCache.getOrPut(this) {
        findAnnotation<EventHandlerType>()?.value
            ?: simpleName
            ?: error("Cannot derive handler type from anonymous class. Use @EventHandlerType.")
    }
