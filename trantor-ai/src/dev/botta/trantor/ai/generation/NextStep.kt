package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.telemetry.GenAITelemetry

/**
 * Decides what each step of a [ToolLoop] goes out with. The loop asks before every step, so that each one can go out
 * differently: a generation answers the same every time, and an agent answers with the agent that has the
 * conversation at that moment, its instructions evaluated again and the history cut down to what the model needs.
 *
 * The loop keeps the rest to itself — the conversation, the limit of steps, the errors of the tools, the parallel
 * calls, the cancellation and the stream — so there is one loop whatever runs on it.
 */
fun interface NextStep {
    /**
     * @param request what the run was asked, with the whole conversation so far as its messages: the ones it started
     * with, then every answer of the model and the results of its tools. What the step sends of it is up to the
     * [StepSetup]; the conversation the loop keeps stays whole.
     * @param steps the steps done, the last of them the one whose tools just ran.
     */
    fun setUp(request: ChatRequest, steps: List<Step>): StepSetup

    companion object {
        /** Every step with the same model and tools and the request as it is, which is what a generation does. */
        fun fixed(model: ChatModel, tools: List<Tool<*>>, searchable: List<Tool<*>> = emptyList()) =
            NextStep { request, _ -> StepSetup(model, request, tools, searchable = searchable) }
    }
}

/**
 * What one step goes out with: the model it calls, the request it sends and the tools that answer the calls of that
 * answer. The loop adds what the model is told about [tools] to the tools of [request], asking each tool again, so a
 * description that depends on the moment is up to date.
 */
class StepSetup(
    val model: ChatModel,
    val request: ChatRequest,
    val tools: List<Tool<*>> = emptyList(),
    /**
     * The tool whose args are the answer, when the answer is an object asked for as a tool. One of [tools]. With it
     * the run ends when the model calls it, once every call of that step ran, and not when the model answers
     * without calling tools: that answer gets a reminder to call it, once in a row, and a second one ends the run.
     */
    val outputTool: String? = null,
    /** What each tool knows about its call. Without it, the call and the [dev.botta.trantor.ai.RunContext] of the loop. */
    val toolContext: ((ToolCallPart) -> ToolContext)? = null,
    /**
     * The agents a tool of this step can hand the conversation over to, by name. Null where there are none, as in
     * a generation, whose handoffs are left with a warning.
     */
    val team: Set<String>? = null,
    /** The agent the step goes out as, which its answer is kept as written by. Null in a generation. */
    val agent: String? = null,
    /** What the loop calls around the model and the tools of the step. */
    val hooks: StepHooks? = null,
    /**
     * The tools the model searches for instead of being told about them up front. The loop tells it about the ones
     * it found, which the conversation says, and gives it the tool to search with.
     */
    val searchable: List<Tool<*>> = emptyList(),
) {
    /**
     * The invocation the step is part of — the agent or the stretch of an agent — when whoever runs the loop traces
     * the run: the spans of the step hang from it, and its calls count in it.
     */
    internal var invocation: GenAITelemetry.OpenSpan? = null
}
