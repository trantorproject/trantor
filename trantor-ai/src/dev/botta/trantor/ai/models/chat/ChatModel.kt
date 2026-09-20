package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.CallOptions

interface ChatModel {
    val provider: String
    val modelId: String

    fun generate(request: ChatRequest, options: CallOptions = CallOptions()): ChatResponse

    fun stream(request: ChatRequest, options: CallOptions = CallOptions()): ChatStream
}
