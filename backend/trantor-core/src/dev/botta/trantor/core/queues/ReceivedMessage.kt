package dev.botta.trantor.core.queues

interface ReceivedMessage {
    val id: String
    val message: Message
    val attempts: Int
}
