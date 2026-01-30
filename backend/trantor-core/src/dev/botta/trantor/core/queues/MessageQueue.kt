package dev.botta.trantor.core.queues

interface MessageQueue {
    val name: String

    fun push(message: Message, options: PushOptions = PushOptions())
    fun poll(): List<ReceivedMessage>
    fun clear()
    fun size(): Int?
    fun delete(message: ReceivedMessage)
}
