package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.tools.Tool

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
        fun fixed(model: ChatModel, tools: List<Tool<*>>) = NextStep { request, _ -> StepSetup(model, request, tools) }
    }
}

/**
 * What one step goes out with: the model it calls, the request it sends and the tools that answer the calls of that
 * answer. The loop adds what the model is told about [tools] to the tools of [request], asking each tool again, so a
 * description that depends on the moment is up to date.
 */
class StepSetup(val model: ChatModel, val request: ChatRequest, val tools: List<Tool<*>> = emptyList())
