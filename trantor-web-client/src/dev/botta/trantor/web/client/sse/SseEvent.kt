package dev.botta.trantor.web.client.sse

data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retry: Long? = null,
)
