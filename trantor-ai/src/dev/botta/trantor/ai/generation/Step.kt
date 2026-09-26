package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.Message
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
    /** The calls of [toolResults] that failed, with their exception. What the model got is in the result itself. */
    val toolFailures: List<ToolFailure> = emptyList(),
    /**
     * What the run told the model after this answer, when the answer could not end it: a reminder to answer by
     * calling the output tool. It is part of the conversation, since the answers after it make no sense without it.
     */
    val reminder: Message.User? = null,
    /** The agent of the team this step handed the conversation over to, which runs the next step. */
    val handoff: String? = null,
    /** What the run itself noticed in this step, apart from what the model call said in [ChatResponse.warnings]. */
    val warnings: List<ModelWarning> = emptyList(),
    /** The agent the step went out as, which wrote its answer. Null in a generation. */
    val agent: String? = null,
    /**
     * The runs of a model the tools of the step made to answer, by the id of their call, like the one of an agent
     * that ran as a tool. The run counts their usage and their cost as its own.
     */
    val toolRuns: Map<String, RunResult> = emptyMap(),
)
