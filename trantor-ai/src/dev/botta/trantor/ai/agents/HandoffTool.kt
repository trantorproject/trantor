package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable

/**
 * The tool a declared handoff turns into. It answers what a tool of the application that transfers would answer,
 * [ToolResult.handoffTo], so the runner has one way of handing over whoever asked for it.
 *
 * It only reads: the conversation changes hands once the step is over, not when the tool runs.
 */
internal class HandoffTool(val agent: String, override val description: String): Tool<HandoffTool.NoArgs>(
    NoArgs.serializer(),
) {
    override val name = toolName(agent)
    override val readOnly = true

    override fun execute(args: NoArgs, context: ToolContext) =
        ToolResult.text("Transferred to $agent.").handoffTo(agent)

    @Serializable
    class NoArgs

    companion object {
        fun toolName(agent: String) = "transfer_to_$agent"

        fun describe(agent: String) = "Hands the conversation over to the $agent agent."
    }
}
