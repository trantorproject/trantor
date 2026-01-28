package dev.botta.trantor.core.events

import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.primitives.events.*
import dev.botta.trantor.primitives.lang.shortName
import dev.botta.trantor.primitives.logging.getLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class DefaultEventDispatcher(private val transactionManager: TransactionManager): EventDispatcher {
    private val logger = getLogger()
    private val handlers = CopyOnWriteArrayList<EventHandler>()
    private val isDeferring = ThreadLocal.withInitial { false }
    private val deferredEvents = ThreadLocal.withInitial { mutableListOf<Event>() }

    override fun publish(event: Event) {
        if (isDeferring.get()) {
            deferredEvents.get().add(event)
            return
        }
        dispatch(event)
    }

    private fun dispatch(event: Event) {
        logger.info("Publish event $event")

        handlers
            .filter { it.canHandle(event) }
            .forEach { dispatchToHandler(event, it) }
    }

    private fun dispatchToHandler(event: Event, handler: EventHandler) {
        if (transactionManager.activeTransaction == null ||
            handler.afterCommit == null ||
            handler.afterCommit == false
        ) {
            invokeEventHandler(event, handler)
            return
        }
        transactionManager.activeTransaction!!.afterCommit {
            invokeEventHandler(event, handler)
        }
    }

    private fun invokeEventHandler(event: Event, handler: EventHandler) {
        logger.info("Invoking event handler ${handler::class.java.shortName()}")
        try {
            handler.on(event)
        } catch (e: Exception) {
            logger.error("Event handler failed: ${e.message}", e)
        }
    }

    override fun defer(block: () -> Unit) {
        if (isDeferring.get()) {
            block()
            return
        }

        isDeferring.set(true)
        try {
            block()
        } finally {
            isDeferring.set(false)
            val buffer = deferredEvents.get()
            val events = buffer.toList()
            buffer.clear()

            events.forEach { dispatch(it) }
        }
    }

    override fun subscribe(handler: EventHandler) {
        handlers.add(handler)
    }

    override fun <T: Event> on(eventType: KClass<T>, listener: EventListener<T>) {
        subscribe(EventListenerHandler(eventType, listener))
    }

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }
}
