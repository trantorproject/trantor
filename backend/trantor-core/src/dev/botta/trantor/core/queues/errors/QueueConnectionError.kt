package dev.botta.trantor.core.queues.errors

open class QueueConnectionError(message: String, cause: Exception? = null): MessageQueueError(message, cause) {
}
