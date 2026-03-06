package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.queues.EnqueueOptions

interface JobDispatcher {
    fun dispatch(job: Job, queueName: String? = null, options: EnqueueOptions = EnqueueOptions())
    fun <T: Job> registerHandler(jobType: Class<T>, handler: JobHandler<T>)
}

inline fun <reified T: Job> JobDispatcher.registerHandler(handler: JobHandler<T>) {
    registerHandler(T::class.java, handler)
}
