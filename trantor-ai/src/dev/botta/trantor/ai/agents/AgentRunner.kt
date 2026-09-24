package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.generation.NextStep
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
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
 */
class AgentRunner(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
) {
    /** Runs [agent] on the conversation so far, whose last message is usually what the user just said. */
    fun run(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        run(agent, conversation.toList(), configure)

    fun run(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunResult {
        val options = AgentRunOptions().apply(configure)
        val run = Run(agent, teamOf(agent, options.team), options, UUID.randomUUID().toString())
        val result = ToolLoop(run, options.maxSteps, options.context, errorHandlers.all)
            .run(ChatRequest(conversation), options.callOptions)

        return AgentRunResult(result, run.agents, run.id)
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
        private val options: AgentRunOptions,
        val id: String,
    ): NextStep {
        /** The agent of each step so far, in order. */
        val agents = mutableListOf<Agent>()

        // Resolved once per agent and run, since every step of an agent calls the same model
        private val chatModels = mutableMapOf<Agent, ChatModel>()

        override fun setUp(request: ChatRequest, steps: List<Step>): StepSetup {
            // The step before handed the conversation over, and its calls already ran with the agent that asked for them
            steps.lastOrNull()?.handoff?.let { agent = team.getValue(it) }

            val agent = agent
            val run = options.context

            agents.add(agent)

            return StepSetup(
                model = chatModels.getOrPut(agent) { agent.modelFrom(models) },
                request = request.copy(
                    messages = listOfNotNull(agent.instructions(run)?.let(Message::system)) + request.messages,
                    dynamicSystem = agent.dynamicInstructions(run),
                    output = agent.output?.takeIf { it.mode == OutputMode.Native }?.let { OutputSpec.Json(it.schema) }
                        ?: OutputSpec.Text,
                    settings = agent.settings,
                    providerOptions = ProviderOptions.of(*(agent.options + options.providerOptions).toTypedArray()),
                ),
                tools = agent.tools,
                outputTool = agent.output?.tool?.name,
                toolContext = { call -> AgentToolContext(call.callId, call.toolName, run, agent, id, team.keys) },
                team = team.keys,
            )
        }
    }
}
