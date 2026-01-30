package dev.botta.trantor.queues

import dev.botta.trantor.aws.addAws
import dev.botta.trantor.core.queues.QueueManager
import dev.botta.trantor.di.ServiceRegistry

fun ServiceRegistry.addSqsQueue() {
    addAws()
    configure<QueueManager> { instance, services ->
        instance.addDriver("sqs", services.create<SqsQueueFactory>())
    }
}
