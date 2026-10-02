package dev.botta.trantor.core.queues

import dev.botta.trantor.config.ConfigSection

/** A queue driver: builds the queues of configuration that name it as their `driver`. */
interface QueueFactory {
    /**
     * Builds the queue [name] from [section], the one it is declared in (`jobs.queues.emails`), which holds its
     * `driver` and whatever settings the driver reads. [name] is the `name` of the section, or its key.
     */
    fun createFromConfig(name: String, section: ConfigSection): MessageQueue
}
