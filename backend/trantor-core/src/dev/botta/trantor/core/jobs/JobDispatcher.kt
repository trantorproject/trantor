package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.queues.PushOptions

interface JobDispatcher {
    fun dispatch(job: Job, queueName: String? = null, options: PushOptions = PushOptions())
    fun <T: Job> registerHandler(jobType: Class<T>, handler: JobHandler<T>)
}

inline fun <reified T: Job> JobDispatcher.registerHandler(handler: JobHandler<T>) {
    registerHandler(T::class.java, handler)
}
