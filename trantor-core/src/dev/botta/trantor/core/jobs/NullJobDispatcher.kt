package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.queues.EnqueueOptions

class NullJobDispatcher: JobDispatcher {
    override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
    }

    override fun <T: Job> registerHandler(jobType: Class<T>, handler: JobHandler<T>) {
    }
}
