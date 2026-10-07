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

/**
 * Hands each event to the handlers subscribed to its type, after the commit of the transaction in course for the
 * ones that ask for it.
 *
 * **What a failure does depends on where the handler runs.** In line, it is logged and nothing else: the other
 * handlers still run and whoever published never sees it, since by then the change is committed and there is
 * nobody to retry. Queued, the handler runs as a job of its own, and a failure makes that job fail, so the queue
 * gives it again: a queued handler has to be idempotent. The same goes for a job this application cannot run, with
 * a handler or an event it does not know ([EventHandlerNotRegisteredError], `EventClassNotFound`) or a body that
 * does not parse. How many times it is retried, and where it ends up, is up to the queue.
 */
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

    // Nothing is caught here, unlike in line: a job that throws stays on its queue, and that is the retry
    private fun processQueuedEventHandlerJob(job: ProcessEventHandlerJob) {
        val event = try {
            serializer.deserialize(job.eventType, job.eventBody)
        } catch (e: JsonParseException) {
            // Whoever logs the failure of the job does not log its body, and here it is what went wrong
            logger.error("Event ${job.eventType} for the handler ${job.handlerType} does not parse: ${job.eventBody}")
            throw e
        }
        val handler = getQueuedHandler(job.handlerType)?.takeIf { it.canHandle(event) }
            ?: throw EventHandlerNotRegisteredError(job.handlerType, job.eventType)

        logger.info("Invoking queued event handler ${handler.handlerType}")
        handler.on(event)
    }

    private fun getQueuedHandler(handlerType: String) =
        handlers.singleOrNull { it.queued != null && it.handlerType == handlerType }

    private fun EventHandler.canHandle(event: Event) = eventTypes.any { it.isInstance(event) }
}
