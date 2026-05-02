package dev.botta.trantor.primitives.events

import dev.botta.time.Clock
import dev.botta.trantor.primitives.lang.describe
import java.util.*

abstract class Event(open val id: UUID = UUID.randomUUID()) {
    var occurredAt = Clock.now()
        protected set

    override fun equals(other: Any?) = other is Event && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = describe("id=$id", "occurredAt=$occurredAt")
}
