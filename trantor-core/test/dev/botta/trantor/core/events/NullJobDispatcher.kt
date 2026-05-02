package dev.botta.trantor.core.events

import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.core.queues.EnqueueOptions
import kotlin.reflect.KClass

class NullJobDispatcher: JobDispatcher {
    override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
    }

    override fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
    }
}
