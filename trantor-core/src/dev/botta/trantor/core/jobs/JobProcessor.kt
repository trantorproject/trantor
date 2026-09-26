package dev.botta.trantor.core.jobs

import com.google.gson.JsonParseException
import dev.botta.trantor.core.jobs.serialization.*
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
import io.opentelemetry.api.OpenTelemetry
import kotlin.reflect.KClass

/** Runs the jobs that arrive on [queue] with their handlers, as a hosted service. */
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
        } catch (e: JobClassNotFound) {
            logger.error("Dropping job: ${e.message}. type=${message.message.type}, id=${message.id}", e)
            return
        } catch (e: JsonParseException) {
            logger.error(
                "Dropping job: ${e.message}. type=${message.message.type}, id=${message.id}, body=${message.message.body}",
                e
            )
            return
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
