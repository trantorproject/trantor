package dev.botta.trantor.core.jobs

import com.google.gson.JsonParseException
import dev.botta.trantor.core.jobs.serialization.*
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
import io.opentelemetry.api.OpenTelemetry
import kotlin.reflect.KClass

/**
 * Runs the jobs that arrive on [queue] with their handlers, as a hosted service.
 *
 * **A job that cannot be run is left on the queue, whatever the reason**: its handler threw, it has no handler, its
 * type is one this application does not know or its body does not parse. Some of those mend themselves, as a job of
 * the release being deployed that reaches an instance of the previous one, and the rest end where the queue puts
 * what keeps failing, which is better than nowhere. Nothing is dropped here, so a queue with no limit of attempts
 * and no dead letter queue gives such a job again until it expires.
 */
class JobProcessor(
    private val handlerRegistry: JobHandlerRegistry,
    private val serializer: JobSerializer,
    private val queue: MessageQueue,
    maxConcurrentWorkers: Int = 4,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): HostedService {
    private val logger = getLogger()
    private val messageProcessor = MessageQueueProcessor(queue, ::onMessage, maxConcurrentWorkers, openTelemetry)

    override val name: String get() = "JobProcessor(${queue.name})"

    private fun onMessage(message: ReceivedMessage) {
        val job = try {
            serializer.deserialize(message.message.type, message.message.body)
        } catch (e: JsonParseException) {
            // Whoever logs the failure of the message does not log its body, and here it is what went wrong
            logger.error("Job ${message.message.type} id=${message.id} does not parse: ${message.message.body}")
            throw e
        }

        executeJob(job)
    }

    private fun <T: Job> executeJob(job: T) {
        @Suppress("UNCHECKED_CAST")
        val handler = handlerRegistry.getHandler(job::class as KClass<T>)
        logger.info("Executing job $job")
        handler.execute(job)
        logger.info("Successfully executed job $job")
    }

    override fun start() {
        messageProcessor.start()
    }

    override fun stop(timeoutSeconds: Int) {
        messageProcessor.stop(timeoutSeconds)
    }
}
