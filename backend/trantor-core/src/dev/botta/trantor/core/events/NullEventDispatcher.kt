package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.*
import kotlin.reflect.KClass

class NullEventDispatcher: EventDispatcher {
    override fun publish(event: Event) {
    }

    override fun subscribe(handler: EventHandler) {
    }

    override fun defer(block: () -> Unit) {
        block()
    }

    override fun <T: Event> on(eventType: KClass<T>, listener: EventListener<T>) {
    }
}
