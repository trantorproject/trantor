package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.CallOptions

interface ChatModel {
    val provider: String
    val modelId: String

    /**
     * Whether the provider searches the tools of a call on its own side, for the ones marked with
     * [FunctionToolSpec.deferLoading][dev.botta.trantor.ai.tools.FunctionToolSpec.deferLoading]. The tool loop asks
     * it to tell those tools to the provider deferred, or to search them itself when it does not.
     */
    val searchesTools: Boolean get() = false

    fun generate(request: ChatRequest, options: CallOptions = CallOptions()): ChatResponse

    fun stream(request: ChatRequest, options: CallOptions = CallOptions()): ChatStream
}
