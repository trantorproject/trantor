package dev.botta.trantor.ai.agents

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.NextStep
import dev.botta.trantor.ai.generation.StepHooks
import dev.botta.trantor.ai.generation.ToolFailure
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.generation.ToolApproval
import dev.botta.trantor.ai.generation.ToolCheck
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
import dev.botta.trantor.ai.telemetry.AITelemetrySettings
import dev.botta.trantor.ai.telemetry.GenAITelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
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
 *
 * With an [openTelemetry] that exports, a run is traced under the span that was current when it was called: an
 * `invoke_agent {agent}` span for an agent alone, and an `invoke_workflow {agent}` span for a team, named after the
 * agent it starts with, with an `invoke_agent` span for each stretch in which an agent had the conversation. Inside
 * go the calls to the model, the tools and a `run_guardrail` span for each guardrail asked. A run of an agent used
 * as a tool is never a workflow, since it is a detail of the tool that runs it. `addAI` passes the one of the
 * container.
 */
class AgentRunner(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
    /** Called around the steps of every run, before the hooks of the agent and those of the run. */
    private val hooks: GlobalAgentHooks = GlobalAgentHooks(),
    /** Asked in every run, before the guardrails of the agent and those of the run. */
    private val guardrails: GlobalGuardrails = GlobalGuardrails(),
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
    /** Whether the spans carry what was said, which they do not unless asked. */
    private val telemetrySettings: AITelemetrySettings = AITelemetrySettings(),
) {
    private val telemetry = GenAITelemetry(openTelemetry, telemetrySettings)

    /**
     * Runs [agent] on the conversation so far, whose last message is usually what the user just said. With a
     * session, the conversation is what the session holds and then these.
     */
    fun run(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        run(agent, conversation.toList(), configure)

    fun run(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunResult {
        val run = start(agent, conversation, configure, telemetry)

        return telemetry.agentRun(agent.name, run.isWorkflow) { span ->
            run.span = span
            run.beforeRun()
            run.checkInput()

            val result = run.resultOf(run.steps())
            run.checkOutput(result)

            run.compacted(result).also { run.finish(it) }
        }
    }

    /** Runs [agent] like [run], received as it happens. See [AgentRunStream]. */
    fun stream(agent: Agent, vararg conversation: Message, configure: AgentRunOptions.() -> Unit = {}) =
        stream(agent, conversation.toList(), configure)

    fun stream(agent: Agent, conversation: List<Message>, configure: AgentRunOptions.() -> Unit = {}): AgentRunStream {
        val run = start(agent, conversation, configure, telemetry)

        run.beforeRun()

        val stream = run.loop.stream(ChatRequest(run.conversation), run.options.callOptions, run.options.decisions)

        return AgentRunStream(stream, run)
    }

    private fun start(
        agent: Agent,
        conversation: List<Message>,
        configure: AgentRunOptions.() -> Unit,
        telemetry: GenAITelemetry,
    ): Run {
        val options = AgentRunOptions().apply(configure)

        return Run(agent, teamOf(agent, options.team), options, UUID.randomUUID().toString(), conversation, telemetry)
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
        private val telemetry: GenAITelemetry,
    ): NextStep, StreamedRun {
        private val first = agent

        /** A team is traced as a workflow, unless the run is that of an agent used as a tool. */
        val isWorkflow = team.size > 1 && options.depth == 0

        /** The span of the run, while it runs traced. */
        var span: GenAITelemetry.OpenSpan? = null

        /** The context of whoever asked for the run, which a stream hangs from wherever it is read. */
        private val caller = Context.current()

        /** In a workflow, the span of the agent that has the conversation, and the step it got it at. */
        private var stretch: GenAITelemetry.OpenSpan? = null
        private var stretchAgent: Agent? = null
        private var stretchStart = 0

        /** What its session holds, which the run goes on from. */
        private val kept = options.session?.load().orEmpty()

        /** What the run starts from: what its session holds, and then what it was given. */
        val conversation = kept + given

        /**
         * What the conversation is once the run ended, in the order it is kept: the results of the calls it resolved
         * right after the answer that made them, what it was given and what it added.
         */
        private fun conversationAfter(result: RunResult) = result.keptAfter(conversation, from = kept.size)

        /** The agent of each step so far, in order. */
        private val agents = mutableListOf<Agent>()

        /** The tool loop the run goes on, asking this run what each step goes out with. */
        val loop = ToolLoop(
            this, options.maxSteps, options.context, errorHandlers.all, openTelemetry, telemetrySettings, false,
        )

        /** Runs the steps on the loop, and ends the span of the last agent with them. */
        fun steps(): RunResult = try {
            loop.run(ChatRequest(conversation), options.callOptions, options.decisions).also { endStretch(it.steps) }
        } catch (e: Throwable) {
            stretch?.fail(e)
            stretch = null
            throw e
        }

        /**
         * The invocation a step of [agent] is part of, which its spans hang from and its calls count in. In a
         * workflow, the span of the agent, which a handoff ends and starts again with the other one; without one,
         * the span of the run.
         */
        private fun invocationOf(agent: Agent, model: ChatModel, steps: List<Step>): GenAITelemetry.OpenSpan? {
            val run = span ?: return null

            if (!isWorkflow) {
                if (steps.isEmpty()) run.model(model.modelId)
                return run
            }

            if (agent != stretchAgent) {
                endStretch(steps)
                stretch = telemetry.agent(agent.name, run.context).also { it.model(model.modelId) }
                stretchAgent = agent
                stretchStart = steps.size
            }

            return stretch
        }

        private fun endStretch(steps: List<Step>) {
            stretch?.end(RunResult(steps.drop(stretchStart)).usage)
            stretch = null
        }

        override fun resultOf(result: RunResult) = AgentRunResult(result, agents, id)

        override fun started() {
            span = telemetry.openAgentRun(first.name, isWorkflow, caller)
        }

        override fun stepsEnded(result: RunResult) = endStretch(result.steps)

        override fun ended(result: AgentRunResult) {
            span?.end(result.usage)
        }

        override fun failed(error: Throwable) {
            stretch?.fail(error)
            stretch = null
            span?.fail(error)
        }

        override fun closed() {
            stretch?.cut()
            stretch = null
            span?.cut()
        }

        fun beforeRun() = hooksOf(first).forEach { it.beforeRun(contextOf(first, 0), conversation) }

        /**
         * The run ended well: the hooks hear of it, and then its session keeps what it was given and what it added, or
         * the conversation compacted in place of all it had. Keeping it goes last, so a run that fails anywhere
         * before, a hook included, keeps nothing.
         */
        override fun finish(result: AgentRunResult) {
            val last = result.lastAgent
            val session = options.session
            val compacted = result.compacted

            hooksOf(last).forEach { it.afterRun(contextOf(last, result.steps.size), result) }

            if (compacted != null) session?.replace(compacted.conversation)
            else session?.append(conversationAfter(result.result).drop(kept.size))
        }

        /**
         * [result] with the conversation it keeps compacted, when the run asked for it and its last call went past
         * the tokens. The span of the run is current meanwhile, so the call that compacts is inside it.
         */
        override fun compacted(result: AgentRunResult): AgentRunResult {
            val compaction = options.compaction ?: return result
            val compacted = current {
                compaction.after(result.result, conversationAfter(result.result), options.callOptions)
            }

            return if (compacted === result.result) result else resultOf(compacted)
        }

        private fun <T> current(block: () -> T) = span?.current(block) ?: block()

        override fun checkInput() {
            val run = contextOf(first, 0)

            (guardrails.inputGuardrails + first.inputGuardrails + options.inputGuardrails).forEach { guardrail ->
                val verdict = telemetry.guardrail(guardrail.name, "input", "llm", null, span?.context) {
                    guardrail.check(run, conversation)
                }

                when (verdict) {
                    GuardrailVerdict.Pass -> {}
                    is GuardrailVerdict.Trip -> throw GuardrailTrippedError(
                        guardrail.name, GuardrailKinds.Input, verdict.reason, verdict.details, first, null,
                    )
                }
            }
        }

        /** A run that paused has no answer yet: it is checked once it ends, in the run that picks it up. */
        override fun checkOutput(result: AgentRunResult) {
            if (result.paused) return

            val last = result.lastAgent
            val run = contextOf(last, result.steps.size)

            outputGuardrailsOf(last).forEach { guardrail ->
                val verdict = telemetry.guardrail(guardrail.name, "output", "llm", null, span?.context) {
                    guardrail.check(run, result)
                }

                when (verdict) {
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
                AgentToolContext(callId, toolName, run, agent, id, team.keys, options.callOptions, options.depth)
            }

            agents.add(agent)

            val model = chatModels.getOrPut(agent) { agent.modelFrom(models) }
            val invocation = invocationOf(agent, model, steps)

            return StepSetup(
                model = model,
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
                    telemetry,
                    invocation?.context,
                ),
            ).also { it.invocation = invocation }
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
        private val telemetry: GenAITelemetry,
        /** What the span of each guardrail hangs from: the one of the agent of the step. */
        private val spanParent: Context?,
    ): StepHooks {
        /** The answer of the step, which the loop always hands over before asking about its calls. */
        private lateinit var response: ChatResponse

        override fun beforeModel(request: ChatRequest) = hooks.fold(request) { sent, hook -> hook.beforeModel(step, sent) }

        override fun afterModel(response: ChatResponse) {
            this.response = response
            hooks.forEach { it.afterModel(step, response) }
        }

        /**
         * The first guardrail that rejects the call or stops the run decides, and the ones after it are not asked.
         * One that asks for approval does not decide: the ones after it are still asked, since a person who approves
         * the call would otherwise skip them, and the call waits only if none of them rejects it.
         */
        override fun checkTool(call: ToolCallPart): ToolCheck? {
            val context = toolContext(call.callId, call.toolName)
            var asked = false
            val reasons = mutableListOf<String>()

            for (guardrail in guardrails) {
                val verdict = telemetry.guardrail(guardrail.name, "input", "tool_call", call.callId, spanParent) {
                    guardrail.check(call, context)
                }

                when (verdict) {
                    GuardrailVerdict.Pass -> continue
                    is GuardrailVerdict.Trip -> throw tripped(guardrail.name, verdict, call, response)
                    is ToolGuardrailVerdict.Reject -> return ToolRefusal(
                        verdict.message,
                        "The guardrail ${guardrail.name} rejected ${call.toolName} on call ${call.callId}, which did " +
                            "not run: ${verdict.message}",
                    )
                    is ToolGuardrailVerdict.AskForApproval -> {
                        asked = true
                        verdict.reason?.let { reasons.add(it) }
                    }
                }
            }

            return if (asked) ToolApproval(reasons.joinToString("; ").ifEmpty { null }) else null
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
