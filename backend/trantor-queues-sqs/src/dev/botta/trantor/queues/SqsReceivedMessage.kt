package dev.botta.trantor.queues

import dev.botta.trantor.core.queues.Message
import dev.botta.trantor.core.queues.ReceivedMessage

data class SqsReceivedMessage(
    override val id: String,
    override val message: Message,
    override val attempts: Int,
    val receiptHandle: String,
): ReceivedMessage
