package dev.botta.trantor.primitives.events

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.time.Clock
import dev.botta.trantor.primitives.lang.describe
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val eventTypeCache = ConcurrentHashMap<KClass<*>, String>()

abstract class Event(val id: UUID = UuidCreator.getTimeOrderedEpoch()) {
    val eventType get() = this::class.eventType()
    var occurredAt = Clock.now()
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false
        if (this::class != other::class) return false
        return id == other.id
    }

    override fun hashCode() = id.hashCode()

    override fun toString() = describe("id=$id", "occurredAt=$occurredAt")
}

fun KClass<*>.eventType(): String =
    eventTypeCache.getOrPut(this) {
        findAnnotation<EventType>()?.value
            ?: simpleName
            ?: error("Cannot derive event type from anonymous class. Use @EventType.")
    }
