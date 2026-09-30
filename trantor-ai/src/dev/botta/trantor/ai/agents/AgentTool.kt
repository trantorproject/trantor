package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.errors.NestedApprovalError
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.primitives.serialization.Description

/**
 * An [Agent] another one uses as a tool, made with [Agent.asTool]. The model calls it with a task, the agent runs a
 * run of its own on it, and what it answers is the result of the call. Unlike a handoff, the conversation stays with
 * the agent that called it.
 *
 * - **It gets only the task**, as a message of the user: not the conversation, nor a session, nor a context policy.
 *   The model is told so in the description of the arg, so it writes a task that carries what the agent needs. It
 *   is what OpenAI Agents, ADK, Microsoft and AI SDK do; Mastra passes the conversation along instead.
 * - **It runs for whom the run that called it runs**: the same [dev.botta.trantor.ai.RunContext] and the same call
 *   options, so a cancellation of that run stops this one too, and ends both. The hooks and guardrails registered
 *   globally apply to it as to any run; those of the run that called it do not.
 * - **What goes back** is the text of its answer, or the object as JSON when the agent answers one.
 * - **What it spent is the run's**: the run of the agent stays in the step that called it
 *   ([dev.botta.trantor.ai.generation.Step.toolRuns]), and its usage and cost count as the run's.
 * - **A run of the agent that fails** goes back to the model as any tool that fails does: "Tool execution failed",
 *   with the exception in the tool failures, for the application.
 * - **A run of the agent that waits for approval** fails the run that called it with [NestedApprovalError]: that run
 *   cannot pause in its place. A tool guardrail can ask for approval of the call to the agent instead.
 * - **How deep it goes is bounded**: a run inside [maxDepth] agents that run as tools does not start another, and the
 *   call goes back to the model as an error. No other library bounds it; it is what ends a cycle of agents that use
 *   each other.
 *
 * The events of its run do not reach the stream of the run that called it, which shows it as a tool that starts and
 * finishes.
 */
class AgentTool internal constructor(
    private val agent: Agent,
    private val runner: AgentRunner,
    override val name: String,
    override val description: String,
    private val maxDepth: Int,
    private val configure: AgentRunOptions.() -> Unit,
): Tool<AgentTool.Args>() {
    override fun execute(args: Args, context: ToolContext): ToolResult {
        val depth = ((context as? AgentToolContext)?.depth ?: 0) + 1

        if (depth > maxDepth) {
            throw ToolError("$name runs as a tool at most $maxDepth deep, and here it would run $depth deep")
        }

        val result = runner.run(agent, Message.user(args.task)) {
            context(context.run)
            callOptions(context.callOptions)
            this.depth = depth
            configure()
        }

        if (result.paused) throw NestedApprovalError(
            agent.name,
            "The run of ${agent.name}, used as the tool $name, waits for approval of " +
                result.pending.joinToString { it.call.toolName } + ", which is not supported inside an agent used " +
                "as a tool: ask for approval of the call to $name instead",
        )

        val answer = result.outputAsJson()?.let { ToolResult.json(it) } ?: ToolResult.text(result.text)

        return answer.withRun(result.result)
    }

    data class Args(
        @Description(
            "What to ask of the agent. It sees nothing of this conversation, so the task has to carry everything it " +
                "needs to know.",
        )
        val task: String,
    )

    companion object {
        const val DEFAULT_MAX_DEPTH = 3
    }
}
