package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.tools.ToolContext

/** What a tool knows about its call when an agent makes it: also the agent and the run it belongs to. */
class AgentToolContext(
    callId: String,
    toolName: String,
    run: RunContext,
    /** The agent whose step asked for the call. */
    val agent: Agent,
    /** The run of the agents, the same in every call it makes. */
    val runId: String,
): ToolContext(callId, toolName, run)

/**
 * The context of a tool that only makes sense inside an agent, like one that hands the conversation over. It fails
 * when the tool was called without one, from a generation.
 */
fun ToolContext.agentContext(): AgentToolContext = this as? AgentToolContext
    ?: throw IllegalStateException("$toolName only runs inside an agent, and it was called without one")
