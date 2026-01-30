package dev.botta.trantor.queues

import dev.botta.trantor.core.queues.*

data class SqsReceivedMessage(
    override val id: String,
    override val message: Message,
    override val attempts: Int,
    val receiptHandle: String,
): ReceivedMessage
