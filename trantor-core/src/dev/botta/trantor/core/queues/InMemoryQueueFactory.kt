package dev.botta.trantor.core.queues

import dev.botta.trantor.config.ConfigSection
import dev.botta.trantor.primitives.serialization.JsonSerializer

/** The `memory` driver: builds an [InMemoryQueue] with the [InMemoryQueueSettings] of the section of the queue. */
class InMemoryQueueFactory(private val serializer: JsonSerializer): QueueFactory {
    override fun createFromConfig(name: String, section: ConfigSection): MessageQueue {
        val json = section.toJson()
        val settings = if (json.isNull) InMemoryQueueSettings() else {
            serializer.deserialize(json.toString(), InMemoryQueueSettings::class.java)
        }

        return InMemoryQueue(name, settings)
    }
}
