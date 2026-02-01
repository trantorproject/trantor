package dev.botta.trantor.core.jobs

import com.google.gson.JsonParseException
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.JsonSerializer

class JobProcessor(
    private val handlerRegistry: JobHandlerRegistry,
    private val serializer: JsonSerializer,
    queue: MessageQueue,
    maxConcurrentWorkers: Int = 4,
): HostedService {
    private val logger = getLogger()
    private val messageProcessor = MessageQueueProcessor(queue, ::onMessage, maxConcurrentWorkers)

    private fun onMessage(message: ReceivedMessage) {
        try {
            val jobClass = getJobClass(message.message)
            val job = serializer.deserialize(message.message.body, jobClass)
            val handler = handlerRegistry.getHandler(jobClass)
            logger.info("Executing job $job")
            handler.execute(job)
        } catch (e: JobClassNotFound) {
            logger.error("Dropping job. type=${message.message.type}, id=${message.id}", e)
        } catch (e: JsonParseException) {
            logger.error("Dropping job. type=${message.message.type}, id=${message.id}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getJobClass(message: Message): Class<Job> {
        return try {
            Class.forName(message.type) as Class<Job>
        } catch (e: ClassNotFoundException) {
            throw JobClassNotFound("Job class not found: ${message.type}", e)
        }
    }

    override fun start() {
        messageProcessor.start()
    }

    override fun stop(timeoutSeconds: Int) {
        messageProcessor.stop(timeoutSeconds)
    }
}
