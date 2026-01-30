package dev.botta.trantor.core.queues

data class PushOptions(
    val delaySeconds: Int = 0,
    val groupId: String? = null,
    val deduplicationId: String? = null,
)
