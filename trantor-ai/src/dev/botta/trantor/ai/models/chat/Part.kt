package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.tools.ToolOutput

sealed interface Part {
    val metadata: ProviderMetadata
}

/**
 * Text, with whatever the provider attached to it in [metadata] — citations, for one. It goes back with the text,
 * because a provider that sent it expects to see it again.
 */
data class TextPart(
    val text: String,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part

/**
 * Model reasoning. [text] is the visible summary, which is absent unless it was asked for with
 * [ReasoningSummaries], and [opaque] is the state the provider needs to continue the conversation: it is kept whole
 * and sent back untouched on the next turn.
 *
 * [metadata] says which provider it came from. Reasoning is signed or encrypted per provider, so sending it to a
 * different one is not possible: that adapter drops it with a warning.
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
 * Something the adapter does not model yet. It is kept as it came and sent back to the same provider on the next
 * turn, so that nothing is lost while we catch up with what providers add.
 *
 * [isItem] says where it was: on its own, or inside the content of a message. It has to go back to the same place,
 * or the provider gets a message part where it expects an item.
 */
data class ProviderPart(
    val provider: String,
    val type: String,
    val raw: JsonObject,
    val isItem: Boolean = true,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part
