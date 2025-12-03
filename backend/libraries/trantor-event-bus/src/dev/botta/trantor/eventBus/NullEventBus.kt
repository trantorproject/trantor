package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event

class NullEventBus: EventBus {
    override suspend fun publish(event: Event) {}

    override suspend fun publish(events: List<Event>) {}

    override fun subscribe(handler: EventHandler) {}

    override fun start() {}

    override fun stop() {}
}
