package dev.botta.trantor.queues

import dev.botta.trantor.config.Config
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.primitives.serialization.JsonSerializer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

class SqsQueueFactory(
    private val credentialsProvider: AwsCredentialsProvider,
    private val serializer: JsonSerializer,
): QueueFactory {
    override fun createFromConfig(name: String, config: Config): MessageQueue {
        val settings = SqsQueueSettings()
        val path = "queues.${name}"
        val configRegion = config["$path.region"] ?: config["aws.region"]
        configRegion?.let { settings.region = it }

        val configEndpointOverride = config["$path.endpointOverride"] ?: config["aws.endpointOverride"]
        configEndpointOverride?.let { settings.endpointOverride = it }

        config["$path.pollMaxMessages"]?.toIntOrNull()?.let { settings.pollMaxMessages = it }
        config["$path.pollWaitTimeSeconds"]?.toIntOrNull()?.let { settings.pollWaitTimeSeconds = it }
        config["$path.pollVisibilityTimeout"]?.toIntOrNull()?.let { settings.pollVisibilityTimeout = it }

        return SqsQueue(name, credentialsProvider, serializer, settings)
    }
}
