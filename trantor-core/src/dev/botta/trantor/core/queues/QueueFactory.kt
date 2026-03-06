package dev.botta.trantor.core.queues

import dev.botta.trantor.config.Config

interface QueueFactory {
    fun createFromConfig(name: String, config: Config): MessageQueue
}
