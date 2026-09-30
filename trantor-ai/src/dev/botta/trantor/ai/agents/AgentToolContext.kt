package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.ai.tools.defaultJsonSerializer

/** What a tool knows about its call when an agent makes it: also the agent and the run it belongs to. */
class AgentToolContext(
    callId: String,
    toolName: String,
    run: RunContext,
    /** The agent whose step asked for the call. */
    val agent: Agent,
    /** The run of the agents, the same in every call it makes. */
    val runId: String,
    /** The names of the agents it can hand the conversation over to with [dev.botta.trantor.ai.tools.ToolResult.handoffTo]. */
    val team: Set<String>,
    callOptions: CallOptions = CallOptions(),
    /**
     * How many agents that run as tools this run is inside of: 0 for a run the application started, 1 for the run
     * of an agent another one used as a tool, and so on. See [AgentTool].
     */
    val depth: Int = 0,
    serializer: JsonSerializer = defaultJsonSerializer,
): ToolContext(callId, toolName, run, callOptions, serializer)

/**
 * The context of a tool that only makes sense inside an agent, like one that hands the conversation over. It fails
 * when the tool was called without one, from a generation.
 */
fun ToolContext.agentContext(): AgentToolContext = this as? AgentToolContext
    ?: throw IllegalStateException("$toolName only runs inside an agent, and it was called without one")
