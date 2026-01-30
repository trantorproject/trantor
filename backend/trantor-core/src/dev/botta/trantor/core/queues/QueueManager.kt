package dev.botta.trantor.core.queues

import dev.botta.trantor.config.Config
import dev.botta.trantor.hosting.HostedService

class QueueManager(
    private val config: Config,
): HostedService {
    private val drivers = mutableMapOf<String, QueueFactory>()
    private val queues = mutableMapOf<String, MessageQueue>()

    @Synchronized
    fun addDriver(name: String, factory: QueueFactory) {
        drivers[name.lowercase()] = factory
    }

    @Synchronized
    fun addQueue(name: String, queue: MessageQueue) {
        if (queues.contains(name.lowercase())) error("Queue $name already exists")
        queues[name.lowercase()] = queue
    }

    fun getQueue(name: String): MessageQueue {
        return queues[name.lowercase()] ?: error("Queue $name does not exist")
    }

    override fun start() {
        loadQueuesFromConfig()
    }

    private fun loadQueuesFromConfig() {
        config.getSection("queues").getChildren().forEach {
            val queueName = it.key
            val driverName = it["driver"] ?: error("Invalid configuration: missing driver for queue $queueName")
            val factory = drivers[driverName.lowercase()] ?: error("Queue driver $driverName not registered")
            val queue = factory.createFromConfig(queueName, config)
            addQueue(queueName, queue)
        }
    }

    override fun stop(timeoutSeconds: Int) {
    }
}
