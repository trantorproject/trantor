package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event
import org.slf4j.LoggerFactory

class InProcessEventBus: EventBus() {
    private val logger = LoggerFactory.getLogger(javaClass.name)
    private val handlers = mutableListOf<EventHandler>()
    private var inRequest: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    private var pendingRequestEvents: ThreadLocal<MutableList<Event>> = ThreadLocal.withInitial { mutableListOf() }

    override fun publish(event: Event) {
        if (inRequest.get()) {
            pendingRequestEvents.get().add(event)
            return
        }
        doPublish(event)
    }

    private fun doPublish(event: Event) {
        logger.info("Publish event $event")

        handlers.filter { it.canHandle(event) }.forEach {
            logger.info("Invoking event handler ${it.javaClass.simpleName}")
            it.on(event)
        }
    }

    override fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun subscribe(handler: EventHandler) {
        handlers.add(handler)
    }

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }

    fun preRequest() {
        inRequest.set(true)
    }

    fun postRequest() {
        inRequest.set(false)
        val pendingEvents = pendingRequestEvents.get()
        pendingRequestEvents.set(mutableListOf())
        pendingEvents.forEach { doPublish(it) }
    }
}
