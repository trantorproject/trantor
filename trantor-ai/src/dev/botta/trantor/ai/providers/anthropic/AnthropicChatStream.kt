package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.models.CancellationLink
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ChatStream
import dev.botta.trantor.ai.models.chat.StreamPart
import dev.botta.trantor.web.client.HttpStreamResponse
import dev.botta.trantor.web.client.sse.sseEvents
import kotlin.time.TimeSource

/**
 * A generation being received from the Messages API.
 *
 * Nothing at the end of the stream carries the whole answer, unlike the Responses API: a message opens empty, its
 * blocks arrive one by one, and the last events say how it ended. So the message is put back together here as it
 * arrives and handed to the same [AnthropicResponseMapper] a plain call uses — which is what makes the response of
 * a stream and the response of a read the same thing, down to the usage and the parts.
 *
 * A block is finished when it says so, which for a tool call is the only moment its input is valid json: the input
 * travels as pieces of text that only parse once the last one arrived.
 */
internal class AnthropicChatStream(
    private val modelId: String,
    private val response: HttpStreamResponse,
    private val startedAt: TimeSource.Monotonic.ValueTimeMark,
    private val warnings: List<ModelWarning>,
    private val mapper: AnthropicResponseMapper,
    private val link: CancellationLink,
): ChatStream {
    private val events = response.sseEvents().iterator()
    private val pending = ArrayDeque<StreamPart>()
    private val blocks = LinkedHashMap<Int, Block>()
    private var message: JsonObject? = null
    private var finished = false
    private var closed = false

    override fun hasNext(): Boolean {
        fill()
        link.throwIfCancelled()

        return pending.isNotEmpty()
    }

    override fun next(): StreamPart {
        fill()

        return pending.removeFirstOrNull() ?: throw NoSuchElementException("The stream is over")
    }

    override fun response(): ChatResponse {
        while (hasNext()) next()

        link.throwIfCancelled()

        return mapper.map(assembled(), modelId, startedAt.elapsedNow(), warnings + cutShort())
    }

    override fun close() {
        closed = true
        response.cancel()
        response.close()
        link.close()
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
            "message_start" -> message = data["message"]?.asObject()
            "content_block_start" -> indexOf(data)?.let { blocks[it] = Block(data["content_block"]?.asObject()) }
            "content_block_delta" -> delta(data, type)
            "content_block_stop" -> blockDone(data)
            "message_delta" -> messageDelta(data)
            "message_stop" -> finished = true
            "error" -> throw ProviderError(
                ANTHROPIC_PROVIDER,
                data.path("error.message")?.asString() ?: "The stream failed",
                code = data.path("error.type")?.asString(),
            )
            else -> pending.add(StreamPart.Raw(type, data))
        }
    }

    /**
     * Every delta belongs to a block, and each kind of block has its own name for what it is adding. They are
     * appended to the block as well as handed out, so that whoever reads only the response still gets it whole.
     */
    private fun delta(data: JsonObject, type: String) {
        val block = blocks[indexOf(data)] ?: return
        val delta = data["delta"]?.asObject() ?: return

        when (delta["type"]?.asString()) {
            "text_delta" -> delta["text"]?.asString()?.let {
                block.append("text", it)
                pending.add(StreamPart.TextDelta(it))
            }
            "thinking_delta" -> delta["thinking"]?.asString()?.let {
                block.append("thinking", it)
                pending.add(StreamPart.ReasoningDelta(it))
            }
            // The signature is what carries the thinking to the next turn, and it is not something to show
            "signature_delta" -> delta["signature"]?.asString()?.let { block.append("signature", it) }
            "input_json_delta" -> delta["partial_json"]?.asString()?.let { block.input.append(it) }
            else -> pending.add(StreamPart.Raw(type, data))
        }
    }

    private fun blockDone(data: JsonObject) {
        val block = blocks[indexOf(data)] ?: return

        pending.add(StreamPart.PartDone(mapper.toPart(block.finish())))
    }

    /** What the message turned out to be: how it ended, and the tokens it took, which are counted from the start. */
    private fun messageDelta(data: JsonObject) {
        data["delta"]?.asObject()?.let { overlay(message, it) }
        data["usage"]?.asObject()?.let { usage ->
            val own = message?.get("usage")?.asObject()

            if (own == null) message?.set("usage", usage) else overlay(own, usage)
        }
    }

    private fun overlay(target: JsonObject?, values: JsonObject) {
        values.keys.forEach { target?.set(it, values.getValue(it)) }
    }

    private fun indexOf(data: JsonObject) = data["index"]?.asInt()

    /** The message as a call that did not stream would have returned it. */
    private fun assembled(): JsonObject {
        val json = message ?: Json.obj("model" to modelId).also { message = it }

        json["content"] = Json.array(blocks.values.map { it.finish() })

        return json
    }

    /**
     * A stream that ended before the message did leaves no stop reason, and there is none to invent: what arrived
     * is real and the rest is missing. Saying so is the whole of it.
     */
    private fun cutShort() = if (finished) emptyList() else
        listOf(ModelWarning("The stream ended before the message was finished"))

    /**
     * A content block being received. Text, thinking and the signature arrive as pieces of themselves and are
     * appended in place; the input of a tool call arrives as pieces of json, which is not json until the last one.
     */
    private class Block(start: JsonObject?) {
        val json = start ?: Json.obj()
        val input = StringBuilder()

        fun append(field: String, text: String) {
            json[field] = (json[field]?.asString() ?: "") + text
        }

        fun finish(): JsonObject {
            // A stream cut in the middle of a tool call leaves half an object, and half an object is no input
            if (input.isNotEmpty()) runCatching { Json.parse(input.toString()).asObject() }.getOrNull()
                ?.let { json["input"] = it }

            return json
        }
    }
}
