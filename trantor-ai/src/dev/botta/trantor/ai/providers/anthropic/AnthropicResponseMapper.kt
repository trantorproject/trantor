package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderMetadata
import kotlin.time.Duration

/**
 * Turns a response of the Anthropic Messages API into a [ChatResponse]. One is made for each call, with the [stamp]
 * of its request: what its thinking has to remember ([ThinkingStamp]); and whether its request asked for the notes
 * between tool calls apart from the thinking ([notes]), which makes a thinking block with text a note.
 */
internal class AnthropicResponseMapper(private val stamp: ThinkingStamp? = null, val notes: Boolean = false) {
    fun map(json: JsonObject, modelId: String, latency: Duration, warnings: List<ModelWarning>): ChatResponse {
        val stopReason = json["stop_reason"]?.asString()

        return ChatResponse(
            content = toContent(json) + listOfNotNull(toRefusal(json)),
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

    /**
     * Why the safeguards declined, which Anthropic gives apart from the content: *"A declined request returns HTTP
     * 200 with `stop_reason: "refusal"` and a `stop_details` object naming the policy area"* (models/sonnet-5-5,
     * read on 2026-09-30). The content may be empty, so the explanation is the text of the refusal.
     */
    private fun toRefusal(json: JsonObject): RefusalPart? {
        val details = json["stop_details"]?.asObject()?.takeIf { it["type"]?.asString() == "refusal" } ?: return null

        return RefusalPart(
            text = details["explanation"]?.asString().orEmpty(),
            category = details["category"]?.asString(),
        )
    }

    private fun toContent(json: JsonObject) =
        json["content"]?.asArray().orEmpty().mapNotNull { it.asObject() }.map { toPart(it) }

    /** A single content block. */
    fun toPart(block: JsonObject): Part = when (block["type"]?.asString()) {
        "text" -> TextPart(block["text"]?.asString() ?: "", extrasOf(block))
        "thinking", "redacted_thinking" -> toReasoning(block)
        TOOL_USE, SERVER_TOOL_USE -> toToolCall(block)
        // Anything we don't model yet is kept whole, to send it back on the next turn
        else -> ProviderPart(ANTHROPIC_PROVIDER, block["type"]?.asString() ?: "unknown", block)
    }

    /**
     * A tool Anthropic ran on its side comes as a `server_tool_use` and is marked as such: the application has
     * nothing to run and nothing to answer for it, and its result arrives in the same response.
     *
     * Everything the part does not carry in a field of its own travels in the metadata, the block type included,
     * so that what goes back on the next turn is the block that came.
     */
    private fun toToolCall(block: JsonObject) = ToolCallPart(
        callId = block["id"]?.asString() ?: "",
        toolName = block["name"]?.asString() ?: "",
        input = block["input"]?.asObject() ?: Json.obj(),
        providerExecuted = block["type"]?.asString() == SERVER_TOOL_USE,
        metadata = everythingElse(block, "id", "name", "input"),
    )

    private fun everythingElse(block: JsonObject, vararg carried: String): ProviderMetadata {
        val extras = Json.obj()

        block.keys.filter { it !in carried }.forEach { extras[it] = block.getValue(it) }

        return ProviderMetadata.of(ANTHROPIC_PROVIDER, extras)
    }

    /**
     * The block is kept whole in [ReasoningPart.opaque]: what lets the conversation go on is the signature, an
     * encrypted copy of the whole reasoning, and not the text, which is a summary and may not even be there. It
     * has to travel back exactly as it came, and a run of them cannot be reordered or partly dropped.
     */
    private fun toReasoning(block: JsonObject): ReasoningPart {
        val text = block["thinking"]?.asString()?.ifBlank { null }

        return ReasoningPart(
            text = text,
            opaque = block,
            metadata = ProviderMetadata.of(
                ANTHROPIC_PROVIDER,
                Json.obj("type" to (block["type"] ?: Json.value(""))).let { stamp?.on(it) ?: it },
            ),
            // With the notes apart the thinking comes back empty, so what carries text is a note
            note = notes && text != null,
        )
    }

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
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            // Billed inside output_tokens, which is what Usage means by a subset
            reasoningTokens = json.path("output_tokens_details.thinking_tokens")?.asInt(),
            raw = json,
        )
    }
}


/** What the model asked the application to run, and what Anthropic ran on its own side. */
private const val TOOL_USE = "tool_use"

private const val SERVER_TOOL_USE = "server_tool_use"
