package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.jobs.serialization.JobSerializer
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.di.valueresolvers.config.ConfigValue
import org.slf4j.MDC
import kotlin.reflect.KClass

class DefaultJobDispatcher(
    private val queueRegistry: JobQueueRegistry,
    private val handlerRegistry: JobHandlerRegistry,
    private val serializer: JobSerializer,
    private val transactionManager: TransactionManager,
    @ConfigValue("jobs.afterCommit") private val dispatchAfterCommit: Boolean = true,
): JobDispatcher {
    override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
        val queue = queueRegistry.getQueue(queueName)
        val serialized = serializer.serialize(job)
        val message = Message(serialized.type, serialized.body, MDC.get("cid"))
        if (transactionManager.activeTransaction == null || !dispatchAfterCommit) {
            push(queue, message, options)
            return
        }
        transactionManager.activeTransaction!!.afterCommit {
            push(queue, message, options)
        }
    }

    private fun push(queue: MessageQueue, message: Message, options: EnqueueOptions) {
        queue.enqueue(message, options)
    }

    @Synchronized
    override fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
        serializer.register(jobType)
        handlerRegistry.registerHandler(jobType, handler)
    }
}
