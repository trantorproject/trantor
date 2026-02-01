package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.queues.*
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.di.valueresolvers.config.ConfigValue
import dev.botta.trantor.primitives.serialization.JsonSerializer

class DefaultJobDispatcher(
    private val queueRegistry: JobQueueRegistry,
    private val handlerRegistry: JobHandlerRegistry,
    private val serializer: JsonSerializer,
    private val transactionManager: TransactionManager,
    @ConfigValue("jobs.afterCommit") private val dispatchAfterCommit: Boolean = true,
): JobDispatcher {
    override fun dispatch(job: Job, queueName: String?, options: PushOptions) {
        val queue = queueRegistry.getQueue(queueName)
        val message = Message(job.javaClass.name, serializer.serialize(job))
        if (transactionManager.activeTransaction == null || !dispatchAfterCommit) {
            push(queue, message, options)
            return
        }
        transactionManager.activeTransaction!!.afterCommit {
            push(queue, message, options)
        }
    }

    private fun push(queue: MessageQueue, message: Message, options: PushOptions) {
        queue.push(message, options)
    }

    @Synchronized
    override fun <T: Job> registerHandler(jobType: Class<T>, handler: JobHandler<T>) {
        handlerRegistry.registerHandler(jobType, handler)
    }
}
