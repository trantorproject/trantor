package dev.botta.trantor.ai.agents

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.NextStep
import dev.botta.trantor.ai.generation.StepHooks
import dev.botta.trantor.ai.generation.ToolFailure
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.OutputSpec
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.tools.ToolErrorHandlers
import java.util.UUID

/**
 * Runs agents on the [ToolLoop], the same one a generation runs on: the loop keeps the conversation, the limit of
 * steps and the tools, and the runner says what each step goes out with.
 *
 * ```kotlin
 * val result = agents.run(support, Message.user(text)) {
 *     context(RunContext(state, scope))
 *     maxSteps(12)
 *     options(OpenAIOptions(promptCacheKey = chatId.toString()))
 * }
 * ```
 *
 * Each step goes out with the instructions of the agent first, as a system message the conversation does not keep,
 * then the conversation, and its dynamic instructions last; both are asked again on every step, so a tool that
 * changes what they read is seen on the next one.
 *
 * A tool that answers [dev.botta.trantor.ai.tools.ToolResult.handoffTo] hands the conversation over to another agent
 * of the team: the step finishes with the agent that asked for its calls, and the next one goes out with the other,
 * which reads the whole conversation. The loop settles two handoffs in a step or one outside the team.
 *
 * Every answer is kept signed by the agent that wrote it, and an agent reads the turns of the others as context with
 * their names, not as its own ([OtherAgentsTurns]).
 */
class AgentRunner(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
    /** Called around the steps of every run, before the hooks of the agent and those of the run. */
    private val hooks: GlobalAgentHooks = GlobalAgentHooks(),
) {
    /** Runs [agent] on the conversation so far, whose last message is usually what the user just said. */
    fun run(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        run(agent, conversation.toList(), configure)

    fun run(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunResult {
        val run = start(agent, configure)

        run.beforeRun(conversation)

        return run.resultOf(run.loop.run(ChatRequest(conversation), run.options.callOptions)).also(run::afterRun)
    }

    /** Runs [agent] like [run], received as it happens. See [AgentRunStream]. */
    fun stream(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        stream(agent, conversation.toList(), configure)

    fun stream(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunStream {
        val run = start(agent, configure)

        run.beforeRun(conversation)

        return AgentRunStream(
            run.loop.stream(ChatRequest(conversation), run.options.callOptions),
            run::resultOf,
            run::afterRun,
        )
    }

    private fun start(agent: Agent, configure: AgentRunOptions.() -> Unit): Run {
        val options = AgentRunOptions().apply(configure)

        return Run(agent, teamOf(agent, options.team), options, UUID.randomUUID().toString())
    }

    /**
     * The agents of a run by name, the first one first. Checked before calling any model: a declared handoff to an
     * agent outside the team would be a tool that always fails.
     */
    private fun teamOf(first: Agent, others: List<Agent>): Map<String, Agent> {
        val agents = (listOf(first) + others).distinct()
        val team = agents.associateBy { it.name }

        agents.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
            throw IllegalArgumentException("The team has more than one agent called ${it.key}")
        }

        agents.forEach { agent ->
            agent.handoffs.firstOrNull { it !in team }?.let {
                throw IllegalArgumentException(
                    "The agent ${agent.name} hands over to $it, which is not in the team: ${team.keys.joinToString()}",
                )
            }
        }

        return team
    }

    /** One run of the agents: which agent each step goes out with, and what that agent makes of the request. */
    private inner class Run(
        private var agent: Agent,
        private val team: Map<String, Agent>,
        val options: AgentRunOptions,
        val id: String,
    ): NextStep {
        /** The agent of each step so far, in order. */
        private val agents = mutableListOf<Agent>()

        /** The tool loop the run goes on, asking this run what each step goes out with. */
        val loop = ToolLoop(this, options.maxSteps, options.context, errorHandlers.all)

        fun resultOf(result: RunResult) = AgentRunResult(result, agents, id)

        fun beforeRun(conversation: List<Message>) =
            hooksOf(agent).forEach { it.beforeRun(contextOf(agent, 0), conversation) }

        fun afterRun(result: AgentRunResult) = result.lastAgent.let { last ->
            hooksOf(last).forEach { it.afterRun(contextOf(last, result.steps.size), result) }
        }

        /** Global first, then the agent's, then the run's. */
        private fun hooksOf(agent: Agent) = hooks.all + agent.hooks + options.hooks

        private fun contextOf(agent: Agent, step: Int) = AgentHookContext(agent, id, options.context, step)

        // Resolved once per agent and run, since every step of an agent calls the same model
        private val chatModels = mutableMapOf<Agent, ChatModel>()

        override fun setUp(request: ChatRequest, steps: List<Step>): StepSetup {
            // The step before handed the conversation over, and its calls already ran with the agent that asked for them
            steps.lastOrNull()?.handoff?.let { agent = team.getValue(it) }

            val agent = agent
            val run = options.context
            val hooks = hooksOf(agent)
            val toolContext = { callId: String, toolName: String ->
                AgentToolContext(callId, toolName, run, agent, id, team.keys)
            }

            agents.add(agent)

            return StepSetup(
                model = chatModels.getOrPut(agent) { agent.modelFrom(models) },
                request = request.copy(
                    messages = listOfNotNull(agent.instructions(run)?.let(Message::system)) +
                        OtherAgentsTurns.toldTo(agent.name, request.messages),
                    dynamicSystem = agent.dynamicInstructions(run),
                    output = agent.output?.takeIf { it.mode == OutputMode.Native }?.let { OutputSpec.Json(it.schema) }
                        ?: OutputSpec.Text,
                    settings = agent.settings,
                    providerOptions = ProviderOptions.of(*(agent.options + options.providerOptions).toTypedArray()),
                ),
                tools = agent.tools,
                outputTool = agent.output?.tool?.name,
                toolContext = { call -> toolContext(call.callId, call.toolName) },
                team = team.keys,
                agent = agent.name,
                hooks = hooks.takeIf { it.isNotEmpty() }
                    ?.let { AgentStepHooks(it, contextOf(agent, steps.size + 1), toolContext) },
            )
        }
    }

    /** The hooks of the agent of one step, as the loop calls them, each one getting what the one before returned. */
    private class AgentStepHooks(
        private val hooks: List<AgentHooks>,
        private val step: AgentHookContext,
        private val toolContext: (callId: String, toolName: String) -> AgentToolContext,
    ): StepHooks {
        override fun beforeModel(request: ChatRequest) = hooks.fold(request) { sent, hook -> hook.beforeModel(step, sent) }

        override fun afterModel(response: ChatResponse) = hooks.forEach { it.afterModel(step, response) }

        override fun beforeTool(call: ToolCallPart): JsonObject {
            val context = toolContext(call.callId, call.toolName)

            return hooks.fold(call.input) { input, hook -> hook.beforeTool(call.copy(input = input), context) }
        }

        override fun afterTool(result: ToolResultPart, failure: ToolFailure?) {
            val context = toolContext(result.callId, result.toolName)

            hooks.forEach { it.afterTool(result, failure, context) }
        }
    }
}
