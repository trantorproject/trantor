package dev.botta.trantor.ai.agents

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.NextStep
import dev.botta.trantor.ai.generation.StepHooks
import dev.botta.trantor.ai.generation.ToolFailure
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.generation.ToolRefusal
import dev.botta.trantor.ai.history.projected
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
 *
 * Guardrails can stop a run with a [GuardrailTrippedError]: those of the input before the first call to the model,
 * those of the tools before any call of a step runs, and those of the output on the final answer, before
 * [AgentHooks.afterRun]. See [InputGuardrail], [ToolGuardrail] and [OutputGuardrail].
 */
class AgentRunner(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
    /** Called around the steps of every run, before the hooks of the agent and those of the run. */
    private val hooks: GlobalAgentHooks = GlobalAgentHooks(),
    /** Asked in every run, before the guardrails of the agent and those of the run. */
    private val guardrails: GlobalGuardrails = GlobalGuardrails(),
) {
    /**
     * Runs [agent] on the conversation so far, whose last message is usually what the user just said. With a
     * session, the conversation is what the session holds and then these.
     */
    fun run(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        run(agent, conversation.toList(), configure)

    fun run(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunResult {
        val run = start(agent, conversation, configure)

        run.beforeRun()
        run.checkInput()

        return run.resultOf(run.loop.run(ChatRequest(run.conversation), run.options.callOptions)).also {
            run.checkOutput(it)
            run.finish(it)
        }
    }

    /** Runs [agent] like [run], received as it happens. See [AgentRunStream]. */
    fun stream(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        stream(agent, conversation.toList(), configure)

    fun stream(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunStream {
        val run = start(agent, conversation, configure)

        run.beforeRun()

        return AgentRunStream(run.loop.stream(ChatRequest(run.conversation), run.options.callOptions), run)
    }

    private fun start(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit): Run {
        val options = AgentRunOptions().apply(configure)

        return Run(agent, teamOf(agent, options.team), options, UUID.randomUUID().toString(), conversation)
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

    /**
     * One run of the agents: which agent each step goes out with, what that agent makes of the request, and the
     * hooks and guardrails around it.
     */
    private inner class Run(
        private var agent: Agent,
        private val team: Map<String, Agent>,
        val options: AgentRunOptions,
        val id: String,
        /** The messages the run was given, which its session keeps once it ended well. */
        private val given: List<Message>,
    ): NextStep, StreamedRun {
        private val first = agent

        /** What the run starts from: what its session holds, and then what it was given. */
        val conversation = options.session?.load().orEmpty() + given

        /** The agent of each step so far, in order. */
        private val agents = mutableListOf<Agent>()

        /** The tool loop the run goes on, asking this run what each step goes out with. */
        val loop = ToolLoop(this, options.maxSteps, options.context, errorHandlers.all)

        override fun resultOf(result: RunResult) = AgentRunResult(result, agents, id)

        fun beforeRun() = hooksOf(first).forEach { it.beforeRun(contextOf(first, 0), conversation) }

        /**
         * The run ended well: the hooks hear of it, and then its session keeps what it was given and what it added.
         * Keeping it goes last, so a run that fails anywhere before, a hook included, keeps nothing.
         */
        override fun finish(result: AgentRunResult) {
            val last = result.lastAgent

            hooksOf(last).forEach { it.afterRun(contextOf(last, result.steps.size), result) }
            options.session?.append(given + result.newMessages)
        }

        override fun checkInput() {
            val run = contextOf(first, 0)

            (guardrails.inputGuardrails + first.inputGuardrails + options.inputGuardrails).forEach { guardrail ->
                when (val verdict = guardrail.check(run, conversation)) {
                    GuardrailVerdict.Pass -> {}
                    is GuardrailVerdict.Trip -> throw GuardrailTrippedError(
                        guardrail.name, GuardrailKinds.Input, verdict.reason, verdict.details, first, null,
                    )
                }
            }
        }

        override fun checkOutput(result: AgentRunResult) {
            val last = result.lastAgent
            val run = contextOf(last, result.steps.size)

            outputGuardrailsOf(last).forEach { guardrail ->
                when (val verdict = guardrail.check(run, result)) {
                    GuardrailVerdict.Pass -> {}
                    is GuardrailVerdict.Trip -> throw GuardrailTrippedError(
                        guardrail.name, GuardrailKinds.Output, verdict.reason, verdict.details, last, result,
                    )
                }
            }
        }

        // The agent of the step going out, which is the one that answers if the step turns out to be the last
        override fun holdsText() = outputGuardrailsOf(agent).any { it.holdsText }

        /** Global first, then the agent's, then the run's; the guardrails go in the same order. */
        private fun hooksOf(agent: Agent) = hooks.all + agent.hooks + options.hooks

        private fun outputGuardrailsOf(agent: Agent) =
            guardrails.outputGuardrails + agent.outputGuardrails + options.outputGuardrails

        private fun toolGuardrailsOf(agent: Agent) =
            guardrails.toolGuardrails + agent.toolGuardrails + options.toolGuardrails

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
                        projected(options.contextPolicies, OtherAgentsTurns.toldTo(agent.name, request.messages), run),
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
                hooks = AgentStepHooks(
                    hooks,
                    toolGuardrailsOf(agent),
                    contextOf(agent, steps.size + 1),
                    toolContext,
                    trippedOnTool(agent, steps),
                ),
            )
        }

        /**
         * The error of a tool guardrail that tripped. The run left the steps before and this one, whose calls did not
         * run.
         */
        private fun trippedOnTool(agent: Agent, steps: List<Step>) =
            { guardrail: String, verdict: GuardrailVerdict.Trip, call: ToolCallPart, response: ChatResponse ->
                val result = resultOf(RunResult(steps + Step(response, agent = agent.name)))

                GuardrailTrippedError(
                    guardrail, GuardrailKinds.Tool, verdict.reason, verdict.details, agent, result, call,
                )
            }
    }

    /**
     * The hooks and the tool guardrails of the agent of one step, as the loop calls them, each hook getting what the
     * one before returned.
     */
    private class AgentStepHooks(
        private val hooks: List<AgentHooks>,
        private val guardrails: List<ToolGuardrail>,
        private val step: AgentHookContext,
        private val toolContext: (callId: String, toolName: String) -> AgentToolContext,
        private val tripped: (String, GuardrailVerdict.Trip, ToolCallPart, ChatResponse) -> GuardrailTrippedError,
    ): StepHooks {
        /** The answer of the step, which the loop always hands over before asking about its calls. */
        private lateinit var response: ChatResponse

        override fun beforeModel(request: ChatRequest) = hooks.fold(request) { sent, hook -> hook.beforeModel(step, sent) }

        override fun afterModel(response: ChatResponse) {
            this.response = response
            hooks.forEach { it.afterModel(step, response) }
        }

        /** The first guardrail that does not pass decides, and the ones after it are not asked. */
        override fun checkTool(call: ToolCallPart): ToolRefusal? {
            val context = toolContext(call.callId, call.toolName)

            for (guardrail in guardrails) {
                when (val verdict = guardrail.check(call, context)) {
                    GuardrailVerdict.Pass -> continue
                    is GuardrailVerdict.Trip -> throw tripped(guardrail.name, verdict, call, response)
                    is ToolGuardrailVerdict.Reject -> return ToolRefusal(
                        verdict.message,
                        "The guardrail ${guardrail.name} rejected ${call.toolName} on call ${call.callId}, which did " +
                            "not run: ${verdict.message}",
                    )
                }
            }

            return null
        }

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
