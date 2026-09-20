package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.web.client.HttpStreamResponse
import dev.botta.trantor.web.client.sse.sseEvents
import kotlin.time.TimeSource

/**
 * A generation being received from the Responses API.
 *
 * The API sends around sixty kinds of event. Text and reasoning deltas, finished items and the final response are
 * mapped; everything else comes out as [StreamPart.Raw], so a new kind of event is visible instead of lost.
 */
internal class OpenAIChatStream(
    private val modelId: String,
    private val response: HttpStreamResponse,
    private val startedAt: TimeSource.Monotonic.ValueTimeMark,
    private val warnings: List<ModelWarning>,
    private val mapper: OpenAIResponseMapper,
): ChatStream {
    private val events = response.sseEvents().iterator()
    private val pending = ArrayDeque<StreamPart>()
    private val received = mutableListOf<Part>()
    private val text = StringBuilder()
    private var finalResponse: JsonObject? = null
    private var closed = false

    override fun hasNext(): Boolean {
        fill()
        return pending.isNotEmpty()
    }

    override fun next(): StreamPart {
        fill()
        return pending.removeFirstOrNull() ?: throw NoSuchElementException("The stream is over")
    }

    override fun response(): ChatResponse {
        while (hasNext()) next()

        finalResponse?.let { return mapper.map(it, modelId, startedAt.elapsedNow(), warnings) }

        return partialResponse()
    }

    override fun close() {
        closed = true
        response.cancel()
        response.close()
    }

    private fun fill() {
        while (pending.isEmpty() && !closed && events.hasNext()) {
            val event = events.next()
            val data = Json.parse(event.data).asObject() ?: continue

            handle(event.event ?: data["type"]?.asString() ?: "", data)
        }
    }

    private fun handle(type: String, data: JsonObject) {
        when (type) {
            "response.output_text.delta" -> textDelta(data)
            "response.reasoning_summary_text.delta" -> data["delta"]?.asString()
                ?.let { pending.add(StreamPart.ReasoningDelta(it)) }
            "response.output_item.done" -> itemDone(data)
            "response.completed", "response.incomplete", "response.failed" -> finalResponse = data["response"]?.asObject()
            "error" -> throw ProviderError(
                OPENAI_PROVIDER,
                data["message"]?.asString() ?: "The stream failed",
                code = data["code"]?.asString(),
            )
            else -> pending.add(StreamPart.Raw(type, data))
        }
    }

    private fun textDelta(data: JsonObject) {
        val delta = data["delta"]?.asString() ?: return

        text.append(delta)
        pending.add(StreamPart.TextDelta(delta))
    }

    private fun itemDone(data: JsonObject) {
        val item = data["item"]?.asObject() ?: return

        mapper.toParts(item).forEach {
            received.add(it)
            pending.add(StreamPart.PartDone(it))
        }
    }

    /** What was received when the stream ended before the final event, so that a cut stream is still usable. */
    private fun partialResponse() = ChatResponse(
        content = received.ifEmpty { listOf(TextPart(text.toString())) },
        finishReason = FinishReasons.Other,
        info = ResponseInfo(model = modelId, provider = OPENAI_PROVIDER, latency = startedAt.elapsedNow()),
        rawFinishReason = "stream_ended_without_response",
        warnings = warnings + ModelWarning("The stream ended before the final response event"),
    )
}
