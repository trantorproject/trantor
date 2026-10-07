package dev.botta.trantor.core.events

/**
 * A job asks for the queued handler [handlerType] to take an event of type [eventType], and this application has no
 * such handler, or has one that does not take that event. It is what an instance sees in the middle of a deploy,
 * when it gets the work of a handler that only the next version has, so the job fails and the queue gives it again.
 */
open class EventHandlerNotRegisteredError(
    val handlerType: String,
    val eventType: String,
    cause: Throwable? = null,
): RuntimeException("No queued event handler '$handlerType' that takes the event '$eventType' is registered", cause)
