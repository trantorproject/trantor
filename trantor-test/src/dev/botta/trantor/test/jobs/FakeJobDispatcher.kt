package dev.botta.trantor.test.jobs

import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.core.queues.EnqueueOptions
import kotlin.reflect.KClass

/**
 * Records the dispatched jobs instead of enqueueing them, so a test can assert what was dispatched without a queue.
 */
class FakeJobDispatcher: JobDispatcher {
    val dispatchedJobs = mutableListOf<Job>()

    override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
        dispatchedJobs.add(job)
    }

    override fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
    }
}
