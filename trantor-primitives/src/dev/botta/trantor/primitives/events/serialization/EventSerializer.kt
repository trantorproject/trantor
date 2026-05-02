package dev.botta.trantor.primitives.events.serialization

import dev.botta.trantor.primitives.events.Event
import kotlin.reflect.KClass

interface EventSerializer {
    fun register(eventClass: KClass<out Event>)
    fun serialize(event: Event): SerializedEvent
    fun deserialize(serialized: SerializedEvent): Event
    fun isRegistered(eventClass: KClass<out Event>): Boolean
}

inline fun <reified T: Event> EventSerializer.register() = register(T::class)

fun EventSerializer.deserialize(eventType: String, eventBody: String) =
    deserialize(SerializedEvent(eventType, eventBody))
