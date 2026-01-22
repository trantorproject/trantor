package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.events.EventHandler
import dev.botta.trantor.primitives.lang.shortName
import dev.botta.trantor.primitives.logging.getLogger

class InProcessEventBus: EventBus {
    private val logger = getLogger()
    private val handlers = mutableListOf<EventHandler>()

    override fun publish(event: Event) {
        logger.info("Publish event $event")

        handlers.filter { it.canHandle(event) }.forEach {
            logger.info("Invoking event handler ${it::class.java.shortName()}")
            try {
                it.on(event)
            } catch (e: Exception) {
                logger.error("Event handler failed: ${e.message}", e)
            }
        }
    }

    override fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun subscribe(handler: EventHandler) {
        handlers.add(handler)
    }

    override fun start() {}

    override fun stop() {}

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }
}
