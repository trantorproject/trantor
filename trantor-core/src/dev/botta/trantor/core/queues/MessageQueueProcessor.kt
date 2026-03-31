package dev.botta.trantor.core.queues

import dev.botta.trantor.core.queues.errors.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
import org.slf4j.MDC
import java.lang.Thread.sleep
import java.util.concurrent.*

class MessageQueueProcessor(
    private val queue: MessageQueue,
    private val onMessage: (ReceivedMessage) -> Unit,
    private val maxConcurrentWorkers: Int = 4,
): HostedService {
    private val logger = getLogger()
    @Volatile
    private var running = false
    private lateinit var pollerThread: Thread
    private val workerExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("queue:${queue.name}:worker-", 0).factory()
    )
    private val permits = Semaphore(maxConcurrentWorkers)

    override val name: String get() = "MessageQueueProcessor(${queue.name})"

    override fun start() {
        running = true
        pollerThread = Thread.ofVirtual()
            .name("queue:${queue.name}:poller")
            .start {
                MDC.put("src", "queue:${queue.name}")
                try {
                    runPoller()
                } finally {
                    MDC.remove("src")
                }
            }

        logger.info("Queue '${queue.name}' poller started with $maxConcurrentWorkers maxConcurrentWorkers")
    }

    override fun stop(timeoutSeconds: Int) {
        running = false
        pollerThread.interrupt()
        workerExecutor.shutdown()
        try {
            if (!workerExecutor.awaitTermination(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            workerExecutor.shutdownNow()
            Thread.currentThread().interrupt() // must restore interrupted flag
        }
    }

    private fun runPoller() {
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val messages = queue.poll()
                if (messages.isEmpty()) continue
                for (message in messages) {
                    permits.acquire()
                    workerExecutor.submit {
                        try {
                            MDC.put("cid", message.id)
                            MDC.put("src", "queue:${queue.name}")
                            processMessage(message)
                        } finally {
                            MDC.remove("cid")
                            MDC.remove("src")
                            permits.release()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                logger.info("Queue '${queue.name}' poller interrupted")
                Thread.currentThread().interrupt()
                break
            } catch (e: QueueConnectionError) {
                logger.error("Queue '${queue.name}' connection error: ${e.message}", e)
                sleep(10_000)
            } catch (e: MessageQueueError) {
                logger.error("Queue '${queue.name}' failed polling messages: ${e.message}", e)
                sleep(1_000)
            } catch (e: Throwable) {
                logger.error("Queue '${queue.name}' fatal error: ${e.message}", e)
                break
            }
        }
    }

    private fun processMessage(message: ReceivedMessage) {
        try {
            onMessage(message)
            queue.delete(message)
        } catch (e: Throwable) {
            logger.error("Queue '${queue.name}' error processing message id=${message.id} type=${message.message.type}", e)
            // Don't delete, automatic retry
        }
    }
}
