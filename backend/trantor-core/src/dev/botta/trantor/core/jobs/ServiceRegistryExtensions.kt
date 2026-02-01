package dev.botta.trantor.core.jobs

import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addJobProcessor(queueName: String? = null, maxConcurrentWorkers: Int = 4) {
    addHostedService {
        val queue = it.get<JobQueueRegistry>().getQueue(queueName)
        JobProcessor(it.get(), it.get(), queue, maxConcurrentWorkers)
    }
}
