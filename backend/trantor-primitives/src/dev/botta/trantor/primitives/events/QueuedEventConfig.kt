package dev.botta.trantor.primitives.events

data class QueuedEventConfig(val queueName: String? = null, val delaySeconds: Int = 0)
