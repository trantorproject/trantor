package dev.botta.trantor.domain.events

import dev.botta.trantor.events.DomainEvent

class RecordedEvents: List<DomainEvent> {
    private val events = mutableListOf<DomainEvent>()

    override val size: Int
        get() = events.size

    override fun contains(element: DomainEvent): Boolean {
        return events.contains(element)
    }

    override fun containsAll(elements: Collection<DomainEvent>): Boolean {
        return events.containsAll(elements)
    }

    override fun get(index: Int): DomainEvent {
        return events[index]
    }

    override fun indexOf(element: DomainEvent): Int {
        return events.indexOf(element)
    }

    override fun isEmpty(): Boolean {
        return events.isEmpty()
    }

    override fun iterator(): Iterator<DomainEvent> {
        return events.iterator()
    }

    override fun lastIndexOf(element: DomainEvent): Int {
        return events.lastIndexOf(element)
    }

    override fun listIterator(): ListIterator<DomainEvent> {
        return events.listIterator()
    }

    override fun listIterator(index: Int): ListIterator<DomainEvent> {
        return events.listIterator(index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<DomainEvent> {
        return events.subList(fromIndex, toIndex)
    }

    fun clear() {
        events.clear()
    }

    fun consume(): List<DomainEvent> {
        val eventList = events.toList()
        events.clear()
        return eventList
    }

    fun trigger(event: DomainEvent) {
        events.add(event)
    }

    fun triggerAll(events: List<DomainEvent>) {
        events.forEach { trigger(it) }
    }

    override fun equals(other: Any?) = other is RecordedEvents && other.events == events

    override fun hashCode() = events.hashCode()
}
