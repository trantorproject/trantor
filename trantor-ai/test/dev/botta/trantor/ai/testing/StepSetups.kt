package dev.botta.trantor.ai.testing

import dev.botta.trantor.ai.generation.StepHooks
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext

/** A [StepSetup] with what only the agents give one, which tests of the loop set one by one. */
internal fun stepSetup(
    model: ChatModel,
    request: ChatRequest,
    tools: List<Tool<*>> = emptyList(),
    outputTool: String? = null,
    toolContext: ((ToolCallPart) -> ToolContext)? = null,
    team: Set<String>? = null,
    agent: String? = null,
    hooks: StepHooks? = null,
) = StepSetup(model, request, tools, outputTool, toolContext, team, agent, hooks, emptyList(), null)
