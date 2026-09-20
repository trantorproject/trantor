package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderMetadata
import kotlin.time.Duration

/** Turns a response of the Anthropic Messages API into a [ChatResponse]. */
internal class AnthropicResponseMapper {
    fun map(json: JsonObject, modelId: String, latency: Duration, warnings: List<ModelWarning>): ChatResponse {
        val stopReason = json["stop_reason"]?.asString()

        return ChatResponse(
            content = toContent(json),
            finishReason = toFinishReason(stopReason),
            info = ResponseInfo(
                id = json["id"]?.asString(),
                model = json["model"]?.asString() ?: modelId,
                provider = ANTHROPIC_PROVIDER,
                latency = latency,
            ),
            rawFinishReason = stopReason,
            usage = toUsage(json["usage"]?.asObject()),
            warnings = warnings,
        )
    }

    private fun toContent(json: JsonObject) =
        json["content"]?.asArray().orEmpty().mapNotNull { it.asObject() }.map { toPart(it) }

    /** A single content block. */
    fun toPart(block: JsonObject): Part = when (block["type"]?.asString()) {
        "text" -> TextPart(block["text"]?.asString() ?: "", extrasOf(block))
        "thinking", "redacted_thinking" -> toReasoning(block)
        // Anything we don't model yet is kept whole, to send it back on the next turn
        else -> ProviderPart(ANTHROPIC_PROVIDER, block["type"]?.asString() ?: "unknown", block)
    }

    /**
     * The block is kept whole in [ReasoningPart.opaque]: what lets the conversation go on is the signature, an
     * encrypted copy of the whole reasoning, and not the text, which is a summary and may not even be there. It
     * has to travel back exactly as it came, and a run of them cannot be reordered or partly dropped.
     */
    private fun toReasoning(block: JsonObject) = ReasoningPart(
        text = block["thinking"]?.asString()?.ifBlank { null },
        opaque = block,
        metadata = ProviderMetadata.of(ANTHROPIC_PROVIDER, Json.obj("type" to (block["type"] ?: Json.value("")))),
    )

    /**
     * What came with the text and we don't model: citations are the quotes of a document, and Anthropic wants
     * them back with the text on the next turn.
     */
    private fun extrasOf(block: JsonObject): ProviderMetadata {
        val citations = block["citations"]?.asArray()?.takeIf { it.isNotEmpty() } ?: return ProviderMetadata.None

        return ProviderMetadata.of(ANTHROPIC_PROVIDER, Json.obj("citations" to citations))
    }

    private fun toFinishReason(stopReason: String?) = when (stopReason) {
        "end_turn" -> FinishReasons.Stop
        // The model wrote one of the sequences the call asked it to stop at, which is a stop and not a cut
        "stop_sequence" -> FinishReasons.Stop
        "max_tokens" -> FinishReasons.Length
        "tool_use" -> FinishReasons.ToolCalls
        "refusal" -> FinishReasons.Refusal
        else -> FinishReasons.Other
    }

    /**
     * Anthropic counts cache apart: `input_tokens` leaves out what was read from the cache and what was written
     * into it, and each of the three is billed at its own price. [Usage] says the details are **subsets**, so the
     * three are added up into [Usage.inputTokens] and the two that are cache stay named besides. Reading them as
     * they came would make the same field mean one thing here and another in OpenAI, and every sum wrong by
     * however much the cache was used.
     */
    private fun toUsage(json: JsonObject?): Usage {
        if (json == null) return Usage.Unknown

        val input = json["input_tokens"]?.asInt()
        val cacheRead = json["cache_read_input_tokens"]?.asInt()
        val cacheWrite = json["cache_creation_input_tokens"]?.asInt()

        return Usage(
            inputTokens = if (input == null) null else input + (cacheRead ?: 0) + (cacheWrite ?: 0),
            outputTokens = json["output_tokens"]?.asInt(),
            cachedInputTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            // Billed inside output_tokens, which is what Usage means by a subset
            reasoningTokens = json.path("output_tokens_details.thinking_tokens")?.asInt(),
            raw = json,
        )
    }
}
