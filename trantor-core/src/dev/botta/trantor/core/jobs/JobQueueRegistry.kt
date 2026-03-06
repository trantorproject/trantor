package dev.botta.trantor.core.jobs

import dev.botta.trantor.config.Config
import dev.botta.trantor.core.queues.*

class JobQueueRegistry {
    private val drivers = mutableMapOf<String, QueueFactory>()
    private val queues = mutableMapOf<String, MessageQueue>()
    private var defaultQueue: String? = null

    @Synchronized
    fun addQueueDriver(name: String, factory: QueueFactory) {
        drivers[name.lowercase()] = factory
    }

    @Synchronized
    fun addQueue(name: String, queue: MessageQueue) {
        if (defaultQueue == null) defaultQueue = name
        if (queues.contains(name.lowercase())) error("Queue $name already exists")
        queues[name.lowercase()] = queue
    }

    fun getQueue(name: String? = null): MessageQueue {
        if (queues.isEmpty()) error("There are no registered queues")
        val nameOrDefault = name ?: defaultQueue
        return queues[nameOrDefault!!.lowercase()] ?: error("Queue $name does not exist")
    }

    fun getDefaultQueue() = getQueue()

    fun loadFromConfig(config: Config, section: String = "jobs.queues") {
        config.getSection(section).getChildren().forEach {
            if (it.key == "default") return@forEach
            val queueName = it.key
            val driverName = it["driver"] ?: error("Invalid configuration: missing driver for queue $queueName")
            val factory = drivers[driverName.lowercase()] ?: error("Queue driver $driverName not registered")
            val queue = factory.createFromConfig(queueName, config)
            addQueue(queueName, queue)
        }
        defaultQueue = config["$section.default"] ?: queues.keys.firstOrNull()
    }
}
