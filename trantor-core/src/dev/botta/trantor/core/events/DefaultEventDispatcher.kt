package dev.botta.trantor.core.events

import com.google.gson.JsonParseException
import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.core.queues.EnqueueOptions
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.primitives.events.*
import dev.botta.trantor.primitives.events.serialization.*
import dev.botta.trantor.primitives.lang.shortName
import dev.botta.trantor.primitives.logging.getLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class DefaultEventDispatcher(
    private val transactionManager: TransactionManager,
    private val jobDispatcher: JobDispatcher,
    private val serializer: EventSerializer,
): EventDispatcher {
    private val logger = getLogger()
    private val handlers = CopyOnWriteArrayList<EventHandler>()
    private val isDeferring = ThreadLocal.withInitial { false }
    private val deferredEvents = ThreadLocal.withInitial { mutableListOf<Event>() }

    init {
        jobDispatcher.registerHandler<ProcessEventHandlerJob>(ProcessEventHandlerJob.Handler(::processQueuedEventHandlerJob))
    }

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
            invokeOrQueueEventHandler(event, handler)
            return
        }
        transactionManager.activeTransaction!!.afterCommit {
            invokeOrQueueEventHandler(event, handler)
        }
    }

    private fun invokeOrQueueEventHandler(event: Event, handler: EventHandler) {
        val queued = handler.queued
        if (queued != null) {
            val serialized = serializer.serialize(event)
            val job = ProcessEventHandlerJob(handler.handlerType, serialized.type, serialized.body)
            jobDispatcher.dispatch(job, queued.queueName, EnqueueOptions(delaySeconds = queued.delaySeconds))
            return
        }
        invokeEventHandler(event, handler)
    }

    private fun invokeEventHandler(event: Event, handler: EventHandler) {
        logger.info("Invoking event handler ${handler.handlerType}")
        try {
            handler.on(event)
        } catch (e: Throwable) {
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
        if (handler.queued != null && getQueuedHandler(handler.handlerType) != null) {
            error("Cannot subscribe more than one queued handler with handlerType '${handler.handlerType}'")
        }
        handler.eventTypes.forEach { serializer.register(it) }
        handlers.add(handler)
    }

    override fun <T: Event> on(eventType: KClass<T>, listener: EventListener<T>) {
        subscribe(EventListenerHandler(eventType, listener))
    }

    private fun processQueuedEventHandlerJob(job: ProcessEventHandlerJob) {
        try {
            val event = serializer.deserialize(job.eventType, job.eventBody)
            val handler = getQueuedHandler(job.handlerType)
            if (handler == null) {
                logger.error("Skipping event handler ${job.handlerType} processing event ${job.eventType}: handler not registered")
                return
            }
            if (!handler.canHandle(event)) {
                logger.error("Skipping event handler ${job.handlerType} processing event ${job.eventType}: handler cannot handle event type")
                return
            }
            invokeEventHandler(event, handler)
        } catch (e: EventClassNotFound) {
            logger.error("Skipping event handler ${job.handlerType} processing event ${job.eventType}: ${e.message}", e)
        } catch (e: JsonParseException) {
            logger.error(
                "Skipping event handler ${job.handlerType} processing event ${job.eventType}: ${e.message} - ${job.eventBody}",
                e
            )
        }
    }

    private fun getQueuedHandler(handlerType: String) =
        handlers.singleOrNull { it.queued != null && it.handlerType == handlerType }

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }
}
