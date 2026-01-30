package dev.botta.trantor.core.queues.errors

import dev.botta.trantor.core.application.ApplicationError

open class MessageQueueError(message: String, cause: Exception? = null): ApplicationError(message, cause) {
}
