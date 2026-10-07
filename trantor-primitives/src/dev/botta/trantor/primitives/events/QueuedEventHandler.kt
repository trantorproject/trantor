package dev.botta.trantor.primitives.events

/**
 * An [EventHandler] that runs as a job on the queue [queueName], the default one when it says none, after
 * [delaySeconds].
 *
 * **It has to be idempotent**: a handler that throws makes its job fail, and the queue gives the job again as many
 * times as the queue is set to. That is what it is for, when the effect of the handler has to happen.
 */
abstract class QueuedEventHandler(private val queueName: String? = null, private val delaySeconds: Int = 0): EventHandler {
    override val queued = QueuedEventConfig(queueName, delaySeconds)
}
