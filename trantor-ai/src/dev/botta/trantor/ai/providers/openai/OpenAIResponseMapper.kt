package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.models.chat.*
import kotlin.time.Duration

/** Turns a response of the OpenAI Responses API into a [ChatResponse]. */
internal class OpenAIResponseMapper {
    fun map(json: JsonObject, modelId: String, latency: Duration, warnings: List<ModelWarning>): ChatResponse {
        val status = json["status"]?.asString()

        return ChatResponse(
            content = toContent(json),
            finishReason = toFinishReason(status, json),
            info = ResponseInfo(
                id = json["id"]?.asString(),
                model = json["model"]?.asString() ?: modelId,
                provider = OPENAI_PROVIDER,
                latency = latency,
            ),
            rawFinishReason = json.path("incomplete_details.reason")?.asString() ?: status,
            usage = toUsage(json["usage"]?.asObject()),
            warnings = warnings,
        )
    }

    private fun toContent(json: JsonObject) =
        json["output"]?.asArray().orEmpty().mapNotNull { it.asObject() }.flatMap { toParts(it) }

    /** Parts of a single output item. */
    fun toParts(item: JsonObject): List<Part> = when (item["type"]?.asString()) {
        "message" -> toMessageParts(item)
        "function_call" -> listOf(toToolCall(item))
        "reasoning" -> listOf(toReasoning(item))
        // Anything we don't model yet is kept whole, to send it back on the next turn
        else -> listOf(ProviderPart(OPENAI_PROVIDER, item["type"]?.asString() ?: "unknown", item))
    }

    private fun toToolCall(item: JsonObject) = ToolCallPart(
        callId = item["call_id"]?.asString() ?: "",
        toolName = item["name"]?.asString() ?: "",
        input = Json.parse(item["arguments"]?.asString() ?: "{}").asObject() ?: Json.obj(),
        metadata = toCallMetadata(item),
    )

    /**
     * The item id, to match the call when the conversation goes on, and the namespace of a tool its tool search
     * found, which OpenAI refuses the call back without: *"Missing namespace for function_call 'getWeather'"*.
     */
    private fun toCallMetadata(item: JsonObject): ProviderMetadata {
        val kept = Json.obj()
        item["id"]?.asString()?.let { kept["id"] = it }
        item["namespace"]?.asString()?.let { kept["namespace"] = it }

        return if (kept.keys.isEmpty()) ProviderMetadata.None else ProviderMetadata.of(OPENAI_PROVIDER, kept)
    }

    /**
     * The item is kept whole in [ReasoningPart.opaque]: what lets the conversation go on is the encrypted content
     * plus the id, not the summary, and it has to travel back exactly as it came.
     */
    private fun toReasoning(item: JsonObject) = ReasoningPart(
        text = toSummaryText(item),
        opaque = item,
        metadata = item["id"]?.asString()?.let { ProviderMetadata.of(OPENAI_PROVIDER, Json.obj("id" to it)) }
            ?: ProviderMetadata.None,
    )

    /** The summary comes in blocks, and is empty unless it was asked for. */
    private fun toSummaryText(item: JsonObject) = item["summary"]?.asArray().orEmpty()
        .mapNotNull { it.asObject()?.get("text")?.asString() }
        .joinToString("\n\n")
        .ifBlank { null }

    private fun toMessageParts(item: JsonObject) = item["content"]?.asArray().orEmpty().mapNotNull { it.asObject() }
        .map { part ->
            when (part["type"]?.asString()) {
                "output_text" -> TextPart(part["text"]?.asString() ?: "", extrasOf(part))
                "refusal" -> RefusalPart(part["refusal"]?.asString() ?: "")
                // Content of a message, not an item of its own, and it has to go back inside a message
                else -> ProviderPart(OPENAI_PROVIDER, part["type"]?.asString() ?: "unknown", part, insideAMessage)
            }
        }

    /**
     * What came with the text and we don't model: annotations are the citations of a web search, and OpenAI wants
     * them back with the text on the next turn.
     */
    private fun extrasOf(part: JsonObject): ProviderMetadata {
        val annotations = part["annotations"]?.asArray()?.takeIf { it.isNotEmpty() } ?: return ProviderMetadata.None

        return ProviderMetadata.of(OPENAI_PROVIDER, Json.obj("annotations" to annotations))
    }

    private fun toFinishReason(status: String?, json: JsonObject) = when (status) {
        "completed" -> when {
            hasRefusal(json) -> FinishReasons.Refusal
            hasToolCalls(json) -> FinishReasons.ToolCalls
            else -> FinishReasons.Stop
        }
        "incomplete" -> when (json.path("incomplete_details.reason")?.asString()) {
            "max_output_tokens" -> FinishReasons.Length
            "content_filter" -> FinishReasons.ContentFilter
            else -> FinishReasons.Other
        }
        "failed" -> FinishReasons.Error
        else -> FinishReasons.Other
    }

    private fun hasRefusal(json: JsonObject) = toContent(json).any { it is RefusalPart }

    private fun hasToolCalls(json: JsonObject) = toContent(json).any { it is ToolCallPart }

    /**
     * OpenAI reports cached, written and reasoning tokens as details of input and output, which is the contract of
     * [Usage]. A recorded call on gpt-5.6-luna settles it for writes too: 5125 read and 13 written out of 5141.
     */
    private fun toUsage(json: JsonObject?): Usage {
        if (json == null) return Usage.Unknown

        return Usage(
            inputTokens = json["input_tokens"]?.asInt(),
            outputTokens = json["output_tokens"]?.asInt(),
            cacheReadTokens = json.path("input_tokens_details.cached_tokens")?.asInt(),
            cacheWriteTokens = json.path("input_tokens_details.cache_write_tokens")?.asInt(),
            reasoningTokens = json.path("output_tokens_details.reasoning_tokens")?.asInt(),
            raw = json,
        )
    }
}
