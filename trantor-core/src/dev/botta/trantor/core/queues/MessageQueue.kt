package dev.botta.trantor.core.queues

interface MessageQueue {
    val name: String

    fun enqueue(message: Message, options: EnqueueOptions = EnqueueOptions())
    fun poll(): List<ReceivedMessage>
    fun clear()
    fun size(): Int?
    fun delete(message: ReceivedMessage)
}
