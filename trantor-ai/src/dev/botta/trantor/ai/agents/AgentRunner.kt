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
        val run = Run(agent, options, UUID.randomUUID().toString())
        val result = ToolLoop(run, options.maxSteps, options.context, errorHandlers.all)
            .run(ChatRequest(conversation), options.callOptions)

        return AgentRunResult(result, run.agents, run.id)
    }

    /** One run of the agents: which agent each step goes out with, and what that agent makes of the request. */
    private inner class Run(private val agent: Agent, private val options: AgentRunOptions, val id: String): NextStep {
        /** The agent of each step so far, in order. */
        val agents = mutableListOf<Agent>()

        // Resolved once per agent and run, since every step of an agent calls the same model
        private val chatModels = mutableMapOf<Agent, ChatModel>()

        override fun setUp(request: ChatRequest, steps: List<Step>): StepSetup {
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
                toolContext = { call -> AgentToolContext(call.callId, call.toolName, run, agent, id) },
            )
        }
    }
}
