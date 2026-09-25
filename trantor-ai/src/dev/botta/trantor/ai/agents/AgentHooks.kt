package dev.botta.trantor.ai.agents

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.generation.ToolFailure
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart

/**
 * What a run of the agents calls around its steps: when it starts and ends, around each call to the model and
 * around each tool. A hook watches, and two of them can change what goes: [beforeModel] the request of that call and
 * [beforeTool] the args of that tool. Stopping a run is not for hooks but for guardrails.
 *
 * ```kotlin
 * class LogTools: AgentHooks {
 *     override fun afterTool(result: ToolResultPart, failure: ToolFailure?, context: AgentToolContext) {
 *         logger.info("${context.agent.name} ran ${result.toolName}")
 *     }
 * }
 *
 * val support = Agent("support").hooks(LogTools()).build()
 * ```
 *
 * They are declared on the agent, on the run and globally with `addAgentHooks`, and run in that order: global, the
 * agent's, the run's, each one getting what the one before returned. The agent's are those of the agent that has
 * the conversation at that moment, so they change with a handoff. A run and a stream call them alike.
 *
 * An exception in a hook fails the run, even around a tool. The calls of a step whose tools only read run at the
 * same time, so [beforeTool] is called from their threads.
 */
interface AgentHooks {
    /** Before the first call to the model, with the conversation the run got. [run] is of the agent it starts with. */
    fun beforeRun(run: AgentHookContext, conversation: List<Message>) {}

    /** The request as it goes to the model, tools included. What it returns goes instead, for this call alone. */
    fun beforeModel(step: AgentHookContext, request: ChatRequest) = request

    fun afterModel(step: AgentHookContext, response: ChatResponse) {}

    /**
     * The args a call goes to its tool with. What it returns is decoded like the model's own, so args the tool does
     * not take go back to the model as an error. The call the model made stays in the conversation as it made it.
     */
    fun beforeTool(call: ToolCallPart, context: AgentToolContext): JsonObject = call.input

    /** What the model will read of the call, and the exception when the tool failed. */
    fun afterTool(result: ToolResultPart, failure: ToolFailure?, context: AgentToolContext) {}

    /**
     * Once the run ended well, with what it left. [run] is of the agent that answered. A run that fails does not get
     * here, and neither does a stream closed before its end.
     */
    fun afterRun(run: AgentHookContext, result: AgentRunResult) {}
}

/** Where in a run a hook is called. */
class AgentHookContext internal constructor(
    /** The agent that has the conversation. */
    val agent: Agent,
    val runId: String,
    val run: RunContext,
    /** The step, counting from one: the one about to go out or that just did, 0 before the first. */
    val step: Int,
)

/** The hooks every run of the [AgentRunner] calls, added with `addAgentHooks`. */
class GlobalAgentHooks {
    private val hooks = mutableListOf<AgentHooks>()

    val all: List<AgentHooks>
        @Synchronized get() = hooks.toList()

    @Synchronized
    fun add(hooks: AgentHooks) = apply { this.hooks.add(hooks) }
}
