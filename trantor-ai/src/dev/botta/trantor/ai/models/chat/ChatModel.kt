package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.CallOptions

/**
 * A model of a provider, called with a [ChatRequest]: one call, with no tool run. The tool loop and the agents run
 * on it. Its adapter sends what the model takes, drops what it does not with a
 * [ModelWarning][dev.botta.trantor.ai.models.ModelWarning], and reads the answer back into the same shapes for
 * every provider.
 */
interface ChatModel {
    val provider: String
    val modelId: String

    /**
     * Whether the provider loads tools told to it deferred
     * ([FunctionToolSpec.deferLoading][dev.botta.trantor.ai.tools.FunctionToolSpec.deferLoading]) once the search of
     * the application finds them, so that the model sees only those. Where it does not, the tool loop tells the model
     * about the tools found like about any other.
     */
    val loadsDeferredTools: Boolean get() = false

    /** One call, answered whole. */
    fun generate(request: ChatRequest, options: CallOptions = CallOptions()): ChatResponse

    /**
     * One call, answered as it is written. The [ChatStream] has the whole response at its end, and closing it
     * cancels the call.
     */
    fun stream(request: ChatRequest, options: CallOptions = CallOptions()): ChatStream
}
