package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.tools.ToolOutput

sealed interface Part {
    val metadata: ProviderMetadata
}

data class TextPart(
    val text: String,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

/**
 * Model reasoning. [text] is the visible summary, which may be absent, and [opaque] the signed or encrypted state
 * that providers require to continue a conversation. Both travel back untouched on the next turn.
 */
data class ReasoningPart(
    val text: String? = null,
    val opaque: JsonObject? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

/** The model refused to answer. Kept as a refusal instead of being turned into text. */
data class RefusalPart(
    val text: String,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

data class ToolCallPart(
    val callId: String,
    val toolName: String,
    val input: JsonObject,
    // True when the provider ran the tool on its side, like web search
    val providerExecuted: Boolean = false,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

data class ToolResultPart(
    val callId: String,
    val toolName: String,
    val output: ToolOutput,
    val isError: Boolean = false,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

/**
 * An item the adapter does not model yet. It is kept as it came and sent back to the same provider on the next turn,
 * so that nothing is lost while we catch up with what providers add.
 */
data class ProviderPart(
    val provider: String,
    val type: String,
    val raw: JsonObject,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part
