package dev.botta.trantor.core.queues

data class EnqueueOptions(
    val delaySeconds: Int = 0,
    val groupId: String? = null,
    val deduplicationId: String? = null,
)
