package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.jobs.serialization.JobSerializer
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.di.valueresolvers.config.ConfigValue
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import org.slf4j.MDC
import kotlin.reflect.KClass

/**
 * Puts jobs on their queue, after the commit of the transaction in progress unless `jobs.afterCommit` says
 * otherwise, so a job never sees data that was rolled back. A job carries the correlation id and the trace of
 * whoever dispatched it, and sending it is a `send` span.
 */
class DefaultJobDispatcher(
    private val queueRegistry: JobQueueRegistry,
    private val handlerRegistry: JobHandlerRegistry,
    private val serializer: JobSerializer,
    private val transactionManager: TransactionManager,
    @ConfigValue("jobs.afterCommit") private val dispatchAfterCommit: Boolean = true,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): JobDispatcher {
    private val spans = QueueSpans(openTelemetry)

    override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
        val queue = queueRegistry.getQueue(queueName)
        val serialized = serializer.serialize(job)
        val message = Message(serialized.type, serialized.body, MDC.get("cid"))
        // Taken now, since the job may leave on commit, and it belongs to whoever dispatched it
        val trace = Context.current()
        if (transactionManager.activeTransaction == null || !dispatchAfterCommit) {
            push(queue, message, options, trace)
            return
        }
        transactionManager.activeTransaction!!.afterCommit {
            push(queue, message, options, trace)
        }
    }

    private fun push(queue: MessageQueue, message: Message, options: EnqueueOptions, trace: Context) {
        spans.send(queue, message, trace) { queue.enqueue(it, options) }
    }

    @Synchronized
    override fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
        serializer.register(jobType)
        handlerRegistry.registerHandler(jobType, handler)
    }
}
