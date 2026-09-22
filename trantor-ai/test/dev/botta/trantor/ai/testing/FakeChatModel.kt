package dev.botta.trantor.ai.testing

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import kotlin.time.Duration.Companion.milliseconds

/** Chat model that answers with what it was given and records the request. */
class FakeChatModel(
    var parts: List<StreamPart> = emptyList(),
    override val modelId: String = "fake-model",
    override val provider: String = "fake",
    var usage: Usage = Usage.Unknown,
): ChatModel {

    var request: ChatRequest? = null
    var options: CallOptions? = null
    var streamClosed = false

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        this.request = request
        this.options = options

        return response()
    }

    override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
        this.request = request
        this.options = options

        return FakeStream()
    }

    private fun response() = ChatResponse(
        content = listOf(TextPart("ok")),
        finishReason = FinishReasons.Stop,
        info = ResponseInfo(model = modelId, provider = provider, latency = 1.milliseconds),
        usage = usage,
    )

    private inner class FakeStream: ChatStream {
        private val remaining = parts.toMutableList()

        override fun hasNext() = remaining.isNotEmpty()

        override fun next() = remaining.removeFirst()

        override fun response() = this@FakeChatModel.response()

        override fun close() {
            streamClosed = true
        }
    }
}
