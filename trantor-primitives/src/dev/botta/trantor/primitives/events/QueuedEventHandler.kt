package dev.botta.trantor.primitives.events

abstract class QueuedEventHandler(private val queueName: String? = null, private val delaySeconds: Int = 0): EventHandler {
    override val queued = QueuedEventConfig(queueName, delaySeconds)
}
