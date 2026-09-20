package dev.botta.trantor.ai.providers.openai

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
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
                provider = OpenAIResponsesModel.PROVIDER,
                latency = latency,
            ),
            rawFinishReason = json.path("incomplete_details.reason")?.asString() ?: status,
            usage = toUsage(json["usage"]?.asObject()),
            warnings = warnings,
        )
    }

    private fun toContent(json: JsonObject) = json["output"]?.asArray().orEmpty().mapNotNull { it.asObject() }
        .flatMap { item ->
            when (item["type"]?.asString()) {
                "message" -> toMessageParts(item)
                // Anything we don't model yet is kept whole, to send it back on the next turn
                else -> listOf(ProviderPart(OpenAIResponsesModel.PROVIDER, item["type"]?.asString() ?: "unknown", item))
            }
        }

    private fun toMessageParts(item: JsonObject) = item["content"]?.asArray().orEmpty().mapNotNull { it.asObject() }
        .map { part ->
            when (part["type"]?.asString()) {
                "output_text" -> TextPart(part["text"]?.asString() ?: "")
                "refusal" -> RefusalPart(part["refusal"]?.asString() ?: "")
                else -> ProviderPart(OpenAIResponsesModel.PROVIDER, part["type"]?.asString() ?: "unknown", part)
            }
        }

    private fun toFinishReason(status: String?, json: JsonObject) = when (status) {
        "completed" -> if (hasRefusal(json)) FinishReasons.Refusal else FinishReasons.Stop
        "incomplete" -> when (json.path("incomplete_details.reason")?.asString()) {
            "max_output_tokens" -> FinishReasons.Length
            "content_filter" -> FinishReasons.ContentFilter
            else -> FinishReasons.Other
        }
        "failed" -> FinishReasons.Error
        else -> FinishReasons.Other
    }

    private fun hasRefusal(json: JsonObject) = toContent(json).any { it is RefusalPart }

    /**
     * OpenAI reports cached, written and reasoning tokens as details of input and output, which is the contract of
     * [Usage]. Whether cache_write_tokens is part of input_tokens is still to be confirmed with a cached call.
     */
    private fun toUsage(json: JsonObject?): Usage {
        if (json == null) return Usage.Unknown

        return Usage(
            inputTokens = json["input_tokens"]?.asInt(),
            outputTokens = json["output_tokens"]?.asInt(),
            cachedInputTokens = json.path("input_tokens_details.cached_tokens")?.asInt(),
            cacheWriteTokens = json.path("input_tokens_details.cache_write_tokens")?.asInt(),
            reasoningTokens = json.path("output_tokens_details.reasoning_tokens")?.asInt(),
            raw = json,
        )
    }
}
