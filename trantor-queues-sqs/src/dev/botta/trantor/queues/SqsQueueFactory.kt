package dev.botta.trantor.queues

import dev.botta.trantor.config.Config
import dev.botta.trantor.config.ConfigSection
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.primitives.serialization.JsonSerializer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

/**
 * The `sqs` driver: builds an [SqsQueue] with the [SqsQueueSettings] of the section of the queue. The `region` and
 * the `endpointOverride` of the queue, when it says none, are those of `aws`, so they are written once for all.
 */
class SqsQueueFactory(
    private val credentialsProvider: AwsCredentialsProvider,
    private val serializer: JsonSerializer,
    private val config: Config,
): QueueFactory {
    override fun createFromConfig(name: String, section: ConfigSection): MessageQueue {
        val settings = serializer.deserialize(section.toJson().toString(), SqsQueueSettings::class.java)
        if (section["region"] == null) config["aws.region"]?.let { settings.region = it }
        if (section["endpointOverride"] == null) config["aws.endpointOverride"]?.let { settings.endpointOverride = it }

        return SqsQueue(name, credentialsProvider, serializer, settings)
    }
}
