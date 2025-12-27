package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event
import dev.botta.trantor.core.lang.shortName
import dev.botta.trantor.core.logging.getLogger
import kotlinx.coroutines.*

class InProcessEventBus: EventBus {
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
            try {
                it.on(event)
            } catch (e: Exception) {
                logger.error("Event handler failed: ${e.message}", e)
            }
        }
    }

    override suspend fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun subscribe(handler: EventHandler) {
        handlers.add(handler)
    }

    override fun start() {}

    override fun stop() {}

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }

    suspend fun <R> inRequest(block: suspend () -> R): R {
        if (currentCoroutineContext()[RequestEventBuffer] != null) return block()
        val buffer = RequestEventBuffer()
        return withContext(buffer) {
            val result = block()
            // Vamos drenando de a poco porque un doPublish puede agregar nuevos eventos a buffer.pending
            while (buffer.pending.isNotEmpty()) {
                val event = buffer.pending.removeAt(0)
                doPublish(event)
            }
            result
        }
    }
}
