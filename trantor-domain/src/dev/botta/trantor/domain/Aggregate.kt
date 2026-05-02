package dev.botta.trantor.domain

import dev.botta.trantor.domain.events.RecordedEvents
import dev.botta.trantor.primitives.lang.describe

abstract class Aggregate<ID>(id: ID) {
    var id = id
        protected set
    val recordedEvents = RecordedEvents()

    override fun equals(other: Any?) = other is Aggregate<*> && other.javaClass == javaClass && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = describe(id)
}
