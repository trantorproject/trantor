package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ToolResultPart

/**
 * One call to the model within a run, and the results of the tools it asked for. A run is the whole of it: from the
 * request to the final answer, one step after another.
 *
 * The OpenAI and Claude agent SDKs call this a turn. Trantor says step, as AI SDK does, because "turn" also means an
 * exchange of a conversation — a message of the user and everything done to answer it — which is a different thing.
 */
data class Step(
    val response: ChatResponse,
    /** In the order of the calls. Empty in the last step, where the model answered without asking for tools. */
    val toolResults: List<ToolResultPart> = emptyList(),
)
