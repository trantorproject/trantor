package dev.botta.trantor.primitives.events

import dev.botta.time.Clock
import java.util.UUID

abstract class Event(open val id: UUID = UUID.randomUUID()) {
    var occurredOn = Clock.now()
        protected set

    override fun equals(other: Any?) = other is Event && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = "${javaClass.name}('$id', occurredOn=$occurredOn)"
}
