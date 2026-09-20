package dev.botta.trantor.ai.models.chat

data class ChatSettings(
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stopSequences: List<String>? = null,
    val seed: Long? = null,
    val reasoning: Reasoning? = null,
    val parallelToolCalls: Boolean? = null,
)
