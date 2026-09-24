package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage

data class ChatResponse(
    val content: List<Part>,
    val finishReason: FinishReasons,
    val info: ResponseInfo,
    // What the provider called the finish reason, when it does not fit the enum
    val rawFinishReason: String? = null,
    val usage: Usage = Usage.Unknown,
    val warnings: List<ModelWarning> = emptyList(),
) {
    /** Every text part joined. The usual way to read a response. */
    val text by lazy { content.filterIsInstance<TextPart>().joinToString("") { it.text } }

    val toolCalls by lazy { content.filterIsInstance<ToolCallPart>() }

    val refusal by lazy { content.filterIsInstance<RefusalPart>().firstOrNull()?.text }

    /** The response as an assistant message, to continue the conversation, written by [agent] if an agent did. */
    fun asMessage(agent: String? = null) = Message.Assistant(content, agent)
}
