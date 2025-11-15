package dev.botta.trantor.domain

import dev.botta.trantor.domain.events.RecordedEvents

abstract class Aggregate<ID: Id>(id: ID) {
    var id = id
        protected set
    val recordedEvents = RecordedEvents()

    override fun equals(other: Any?) = other is Aggregate<*> && other.javaClass == javaClass && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = "${javaClass.simpleName}($id)"
}
