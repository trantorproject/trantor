package dev.botta.trantor.test.events

import dev.botta.trantor.primitives.events.*
import kotlin.reflect.KClass

/**
 * Records the published events instead of dispatching them, so a test can assert what a use case or a repository
 * published. Deferred blocks run right away.
 */
class FakeEventDispatcher: EventDispatcher {
    val publishedEvents = mutableListOf<Event>()

    override fun publish(event: Event) {
        publishedEvents.add(event)
    }

    override fun subscribe(handler: EventHandler) {
    }

    override fun defer(block: () -> Unit) = block()

    override fun <T: Event> on(eventType: KClass<T>, listener: EventListener<T>) {
    }
}
