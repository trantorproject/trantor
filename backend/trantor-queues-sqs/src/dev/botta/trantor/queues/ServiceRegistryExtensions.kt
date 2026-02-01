package dev.botta.trantor.queues

import dev.botta.trantor.aws.addAws
import dev.botta.trantor.core.jobs.JobQueueRegistry
import dev.botta.trantor.di.ServiceRegistry

fun ServiceRegistry.addSqsQueue() {
    addAws()
    configure<JobQueueRegistry> { instance, services ->
        instance.addQueueDriver("sqs", services.create<SqsQueueFactory>())
    }
}
