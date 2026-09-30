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
    /**
     * Whether it is a note the model wrote between tool calls for whoever watches the run — what it found and what
     * it will do next — and not reasoning. [text] is then the note, to show like the text of the answer. It goes
     * back to the provider like any reasoning. See
     * [Notes](https://github.com/nbottarini/trantor/blob/main/docs/trantor-ai.md#notes-between-tool-calls).
     */
    val note: Boolean = false,
): Part

/**
 * The model refused to answer. Kept as a refusal instead of being turned into text. [text] is what the model said, or
 * the explanation of the provider when a safeguard stopped it before it said anything.
 */
data class RefusalPart(
    val text: String,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
    /**
     * Why, when the provider says: Anthropic names the policy of the safeguard that declined (`cyber`, `bio`,
     * `frontier_llm`, `reasoning_extraction`, `general_harms`), which tells an application whether retrying with
     * another model or another prompt is worth it.
     */
    val category: String? = null,
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
 * Where it was — on its own or inside the content of a message — is the provider's own business and travels in
 * [metadata]. Only OpenAI has the two levels; for everyone else there is one, and a shared type has no reason to
 * carry a distinction that belongs to a single wire format.
 */
data class ProviderPart(
    val provider: String,
    val type: String,
    val raw: JsonObject,
    override val metadata: ProviderMetadata = ProviderMetadata.None,
): Part
