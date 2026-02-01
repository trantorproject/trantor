package dev.botta.trantor.queues

import dev.botta.trantor.aws.AWSError
import dev.botta.trantor.core.queues.Message
import dev.botta.trantor.core.queues.MessageQueue
import dev.botta.trantor.core.queues.PushOptions
import dev.botta.trantor.core.queues.ReceivedMessage
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.*
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*
import java.net.URI
import java.util.*

class SqsQueue(
    override val name: String,
    private val credentialsProvider: AwsCredentialsProvider,
    private val serializer: JsonSerializer,
    private val settings: SqsQueueSettings = SqsQueueSettings(),
): MessageQueue {
    private val logger = getLogger()
    val isFifo = name.endsWith(".fifo")

    private var queueUrl: String? = null
    private val client = SqsClient.builder()
        .region(Region.of(settings.region))
        .credentialsProvider(credentialsProvider)
        .endpointOverride(if (settings.endpointOverride.isNullOrEmpty()) null else URI(settings.endpointOverride!!))
        .build()

    @Synchronized
    private fun ensureQueueUrl() {
        if (queueUrl != null) return
        try {
            queueUrl = client.getQueueUrl { it.queueName(name) }.queueUrl()
                ?: throw AWSError("Queue '$name' not found in SQS")
        } catch (e: QueueDoesNotExistException) {
            throw AWSError("Queue '$name' not found in SQS", e)
        }
    }

    override fun push(message: Message, options: PushOptions) {
        ensureQueueUrl()

        client.sendMessage {
            it.queueUrl(queueUrl)
            it.messageBody(serializer.serialize(message))
            if (isFifo) {
                it.messageDeduplicationId(options.deduplicationId ?: UUID.randomUUID().toString())
                it.messageGroupId(options.groupId ?: "default")
            } else {
                it.delaySeconds(options.delaySeconds)
                it.messageGroupId(options.groupId)
            }
        }
    }

    override fun poll(): List<ReceivedMessage> {
        ensureQueueUrl()
        val response = client.receiveMessage {
            it.queueUrl(queueUrl)
            it.maxNumberOfMessages(settings.pollMaxMessages)
            it.waitTimeSeconds(settings.pollWaitTimeSeconds)
            it.visibilityTimeout(settings.pollVisibilityTimeout)
            it.messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
        }
        val messages = response.messages() ?: emptyList()
        return messages.mapNotNull {
            try {
                val message = serializer.deserialize<Message>(it.body())
                val attributes = it.attributes()
                SqsReceivedMessage(
                    it.messageId(),
                    message,
                    attributes[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull() ?: 0,
                    it.receiptHandle(),
                )
            } catch (e: Exception) {
                logger.error("Queue '$name' error deserializing message id=${it.messageId()} body=${it.body()}", e)
                null
            }
        }
    }

    override fun clear() {
        ensureQueueUrl()
        client.purgeQueue {
            it.queueUrl(queueUrl)
        }
    }

    override fun size(): Int? {
        ensureQueueUrl()
        val response = client.getQueueAttributes {
            it.queueUrl(queueUrl)
            it.attributeNames(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
            )
        }
        val attributes = response.attributes()
        val approxNumberOfMessages = attributes[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
        val approxNumberOfMessagesDelayed = attributes[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED]?.toIntOrNull() ?: 0
        val approxNumberOfMessagesNotVisible = attributes[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0

        return approxNumberOfMessages + approxNumberOfMessagesDelayed + approxNumberOfMessagesNotVisible
    }

    override fun delete(message: ReceivedMessage) {
        if (message !is SqsReceivedMessage) error("message must be instance of SqsReceivedMessage")

        ensureQueueUrl()
        client.deleteMessage {
            it.queueUrl(queueUrl)
            it.receiptHandle(message.receiptHandle)
        }
    }
}
