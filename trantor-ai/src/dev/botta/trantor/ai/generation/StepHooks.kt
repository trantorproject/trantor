package dev.botta.trantor.ai.generation

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart

/**
 * What the [ToolLoop] calls around the model and the tools of one step, when its [StepSetup] brings them. It is how
 * the hooks of an agent reach the loop, those of the agent that has the conversation in that step.
 *
 * An exception in any of them fails the run, even around a tool: it is not a failure of the tool, which the model
 * could work around, but of the application.
 */
interface StepHooks {
    /** The request as it goes to the model, tools included. What it returns goes instead, for this call alone. */
    fun beforeModel(request: ChatRequest) = request

    fun afterModel(response: ChatResponse) {}

    /**
     * The args a call goes to its tool with. What it returns is decoded like the model's own, so args the tool does
     * not take go back to the model as an error. The call the model made stays as it made it.
     */
    fun beforeTool(call: ToolCallPart) = call.input

    /** What the model will read of the call, once its handoff, if it made one, was settled. */
    fun afterTool(result: ToolResultPart, failure: ToolFailure?) {}
}

/** The hooks that change nothing, for a step that brings none. */
internal object NoStepHooks: StepHooks
