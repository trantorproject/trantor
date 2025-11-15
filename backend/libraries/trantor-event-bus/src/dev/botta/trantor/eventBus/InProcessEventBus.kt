package dev.botta.trantor.eventBus

import dev.botta.trantor.core.*
import dev.botta.trantor.core.lang.shortName
import kotlinx.coroutines.*

class InProcessEventBus: EventBus() {
    private val logger = getLogger()
    private val handlers = mutableListOf<EventHandler>()

    override suspend fun publish(event: Event) {
        val buffer = currentCoroutineContext()[RequestEventBuffer]
        if (buffer != null) {
            buffer.pending += event
            return
        }
        doPublish(event)
    }

    private suspend fun doPublish(event: Event) {
        logger.info("Publish event $event")

        handlers.filter { it.canHandle(event) }.forEach {
            logger.info("Invoking event handler ${it::class.java.shortName()}")
            it.on(event)
        }
    }

    override suspend fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun subscribe(handler: EventHandler) {
        handlers.add(handler)
    }

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }

    suspend fun <R> inRequest(block: suspend () -> R): R {
        val buffer = RequestEventBuffer()
        return withContext(buffer) {
            val result = block()
            buffer.pending.forEach { doPublish(it) }
            result
        }
    }
}
