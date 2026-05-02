package dev.botta.trantor.core.jobs

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class JobHandlerRegistry {
    private val handlers = ConcurrentHashMap<KClass<*>, JobHandler<out Job>>()

    @Synchronized
    fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
        if (handlers.contains(jobType)) error("Handler for $jobType already registered")
        handlers[jobType] = handler
    }

    @Suppress("UNCHECKED_CAST")
    fun <T: Job> getHandler(jobType: KClass<T>): JobHandler<T> {
        return handlers[jobType] as? JobHandler<T> ?: error("Handler not registered for $jobType")
    }
}
