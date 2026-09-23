package dev.botta.trantor.ai.testing

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.chat.FinishReasons.Stop
import dev.botta.trantor.ai.models.chat.FinishReasons.ToolCalls
import kotlin.time.Duration.Companion.milliseconds

/**
 * Chat model that answers with what it was given and records the request.
 *
 * [responses] scripts a conversation: each generate takes the next one, and once they run out it answers "ok".
 * [streams] does the same for the parts of each stream, falling back to [parts].
 */
class FakeChatModel(
    var parts: List<StreamPart> = emptyList(),
    override val modelId: String = "fake-model",
    override val provider: String = "fake",
    var usage: Usage = Usage.Unknown,
): ChatModel {

    val responses = ArrayDeque<ChatResponse>()
    val streams = ArrayDeque<List<StreamPart>>()
    val requests = mutableListOf<ChatRequest>()
    var request: ChatRequest? = null
    var options: CallOptions? = null
    var streamClosed = false
    var streamsClosed = 0

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        this.request = request
        this.options = options
        requests.add(request)

        return nextResponse()
    }

    /** Scripts the next answers, in order. One that calls a tool finishes by tool calls, as a provider does. */
    fun answers(vararg contents: List<Part>) = apply {
        contents.forEach { content ->
            val finishReason = if (content.any { it is ToolCallPart }) ToolCalls else Stop
            responses.add(response(content, finishReason))
        }
    }

    override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
        this.request = request
        this.options = options
        requests.add(request)

        return FakeStream(streams.removeFirstOrNull() ?: parts)
    }

    /** Scripts the parts of the next streams, in order. */
    fun streams(vararg parts: List<StreamPart>) = apply { streams.addAll(parts) }

    /** The next scripted answer, or the default one once they run out. */
    private fun nextResponse() = responses.removeFirstOrNull() ?: response()

    private fun response(content: List<Part> = listOf(TextPart("ok")), finishReason: FinishReasons = Stop) =
        ChatResponse(
            content = content,
            finishReason = finishReason,
            info = ResponseInfo(model = modelId, provider = provider, latency = 1.milliseconds),
            usage = usage,
        )

    private inner class FakeStream(parts: List<StreamPart>): ChatStream {
        private val remaining = parts.toMutableList()

        override fun hasNext() = remaining.isNotEmpty()

        override fun next() = remaining.removeFirst()

        override fun response() = nextResponse()

        override fun close() {
            streamClosed = true
            streamsClosed++
        }
    }
}
