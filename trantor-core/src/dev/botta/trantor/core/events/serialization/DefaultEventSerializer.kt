package dev.botta.trantor.core.events.serialization

import dev.botta.trantor.primitives.events.*
import dev.botta.trantor.primitives.events.serialization.*
import dev.botta.trantor.primitives.serialization.JsonSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class DefaultEventSerializer(
    private val jsonSerializer: JsonSerializer,
): EventSerializer {
    private val byType = ConcurrentHashMap<String, KClass<out Event>>()

    override fun register(eventClass: KClass<out Event>) {
        val type = eventClass.eventType()
        val existing = byType.putIfAbsent(type, eventClass)
        if (existing != null && existing != eventClass) {
            throw EventTypeCollisionError(
                "Event type '$type' is already registered for ${existing.qualifiedName}, " +
                        "cannot register ${eventClass.qualifiedName}. " +
                        "Use @EventType to disambiguate."
            )
        }
    }

    override fun serialize(event: Event) = SerializedEvent(event.eventType, jsonSerializer.serialize(event))

    override fun deserialize(serialized: SerializedEvent): Event {
        val eventClass = byType[serialized.type]
            ?: throw EventClassNotFound("No event class registered for type '${serialized.type}'")

        return jsonSerializer.deserialize(serialized.body, eventClass.java)
    }

    override fun isRegistered(eventClass: KClass<out Event>) = byType[eventClass.eventType()] == eventClass
}
