package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.serialization.defaultJsonSerializer
import dev.botta.trantor.ai.telemetry.AITelemetrySettings
import dev.botta.trantor.ai.telemetry.GenAITelemetry
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.tools.search.ToolSearcher
import dev.botta.trantor.primitives.serialization.JsonSerializer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context

/**
 * Calls the model, runs the tools it asks for and calls it again with their results, until it answers without
 * asking for more.
 *
 * It is the one tool loop of trantor-ai: a generation with tools and an agent both run on it. What each step goes
 * out with — the model, the request and the tools — comes from [nextStep], asked before every step; everything else
 * is the loop's.
 *
 * Each call carries the answer before it whole — reasoning, signatures and parts we do not model — because that is
 * what lets the provider pick up where it left off. Tools the provider ran on its side are not run again: their
 * results already came in the answer.
 *
 * A call whose tool [needs approval][Tool.needsApproval] does not run: the other calls of its step do, and the run
 * ends paused on that step, with the call in [RunResult.pending] and without asking the model again. The run that
 * picks the conversation up answers the calls its last answer left without a result before it calls the model, with
 * the [Decision]s it got: an approved call runs, and any other is answered as not approved.
 *
 * A failing tool does not fail the run. The model gets a result marked as an error and can try something else:
 *
 * - Input that does not fit the args, or a tool that does not exist, go back with the detail, since the model can
 *   fix them.
 * - A [ToolError] goes back with its message, which the tool wrote for the model.
 * - Any other exception goes back as "Tool execution failed", unless one of the [errorHandlers] has something safe
 *   to say about it, because its message was written for a developer and could reach the user.
 *
 * The exception itself stays in [Step.toolFailures] and in the log, for the application. A tool with
 * [ToolErrorModes.FailRun] fails the run with its exception instead, and a cancellation always ends it.
 */
class ToolLoop(
    private val nextStep: NextStep,
    /** How many calls to the model a generation may take. Past them, [MaxStepsExceededError]. */
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    internal val run: RunContext = RunContext(),
    /** Asked in order for a safe message about an exception of a tool; the first that answers wins. */
    internal val errorHandlers: List<ToolErrorHandler> = emptyList(),
    /**
     * What traces the run: an `invoke_agent` span with a `chat` span for each call to the model and an `execute_tool`
     * span for each tool. Without an SDK behind it, it costs nothing.
     */
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
    /** Whether those spans carry what was said, which they do not unless asked. */
    telemetrySettings: AITelemetrySettings = AITelemetrySettings(),
    /**
     * How the tools read their args and write what they answer, and what tells the model their schema: the
     * serializer of the application, with the types it registered.
     */
    internal val serializer: JsonSerializer = defaultJsonSerializer,
) {
    /** A loop whose every step goes out with the same model and tools, as a generation does. */
    constructor(
        model: ChatModel,
        tools: List<Tool<*>>,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        run: RunContext = RunContext(),
        errorHandlers: List<ToolErrorHandler> = emptyList(),
        openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
        telemetrySettings: AITelemetrySettings = AITelemetrySettings(),
        serializer: JsonSerializer = defaultJsonSerializer,
        /** The tools the model searches for, instead of being told about them up front. */
        searchableTools: List<Tool<*>> = emptyList(),
        /** How [searchableTools] are searched, by their words when null; see [ToolSearcher]. */
        toolSearcher: ToolSearcher? = null,
    ): this(
        NextStep.fixed(model, tools, searchableTools, toolSearcher),
        maxSteps,
        run,
        errorHandlers,
        openTelemetry,
        telemetrySettings,
        serializer,
    )

    /**
     * A loop whose run is traced by whoever runs it, as the [dev.botta.trantor.ai.agents.AgentRunner] does: the spans
     * of its steps hang from what each [StepSetup] says.
     */
    internal constructor(
        nextStep: NextStep,
        maxSteps: Int,
        run: RunContext,
        errorHandlers: List<ToolErrorHandler>,
        openTelemetry: OpenTelemetry,
        telemetrySettings: AITelemetrySettings,
        serializer: JsonSerializer,
        tracesItsRun: Boolean,
    ): this(nextStep, maxSteps, run, errorHandlers, openTelemetry, telemetrySettings, serializer) {
        this.tracesItsRun = tracesItsRun
    }

    internal val telemetry = GenAITelemetry(openTelemetry, telemetrySettings)
    private var tracesItsRun = true

    /**
     * Runs [request] to the answer. [decisions] say what a person decided about the calls its conversation left
     * waiting for approval, which the run answers before it calls the model. See [Decision].
     */
    fun run(request: ChatRequest, options: CallOptions = CallOptions(), decisions: List<Decision> = emptyList()) =
        if (tracesItsRun) telemetry.generation { Steps(request, options, decisions, streaming = false, it).result() }
        else Steps(request, options, decisions, streaming = false, generation = null).result()

    /**
     * The same loop, received as it happens. Every event is produced while the run is going, so whoever reads it
     * sees the text and the tools as they happen; closing it stops the run where it is.
     */
    fun stream(
        request: ChatRequest,
        options: CallOptions = CallOptions(),
        decisions: List<Decision> = emptyList(),
    ): RunStream = Steps(request, options, decisions, streaming = true, generation = null)

    /** The steps of a run and the rules that end it. */
    private inner class Run(
        private val request: ChatRequest,
        private val options: CallOptions,
        private val decisions: List<Decision>,
    ) {
        val steps = mutableListOf<Step>()

        /** The whole conversation, whatever each step sent of it. */
        private var messages = request.messages

        /**
         * The calls the conversation left waiting for approval: those of its last answer without a result, which is
         * how a run that paused leaves it. The run answers them before its first step.
         */
        val waiting = WaitingCalls.of(request.messages)

        private val decisionsById = decisions.associateBy { it.callId }

        /** The calls it answered before its first step, once it did. */
        private var resolved: ResolvedCalls? = null

        init {
            // Before anything runs: a decision that answers nothing means the application and the conversation disagree
            decisions.firstOrNull { decision -> waiting.none { it.callId == decision.callId } }?.let {
                throw NoPendingCallError(
                    it.callId,
                    "There is no call ${it.callId} waiting for approval in the conversation: it was answered " +
                        "already, or it never was",
                )
            }
        }

        /** Whether the calls waiting for approval are still to be answered, which the first step does first. */
        val resolving get() = waiting.isNotEmpty() && resolved == null

        /** What was decided about [call], or null when nobody did. */
        fun decisionAbout(call: ToolCallPart) = decisionsById[call.callId]

        /**
         * Keeps what the calls waiting for approval left, and puts their results right after the answer that made
         * them, in the conversation and in what [step] sends.
         */
        fun resolved(executions: List<ToolExecution>, step: OutgoingStep) {
            val calls = ResolvedCalls(
                executions.map { it.result },
                executions.mapNotNull { it.failure },
                waiting.filter { decisionAbout(it) == null }.map {
                    ModelWarning(
                        "The call ${it.callId} to ${it.toolName} was waiting for approval and the run got no " +
                            "decision about it, so it was answered as not approved",
                    )
                },
                executions.mapNotNull { it.run?.let { run -> it.result.callId to run } }.toMap(),
            )

            resolved = calls
            messages = calls.into(messages)
            step.resolved(calls)
        }

        /**
         * Whether an approved call the run answered before its first step handed the conversation over. The step it
         * was answered with is then left unsent, and the first step goes out as the agent it was handed over to.
         */
        fun handedOver(step: OutgoingStep): Boolean {
            val to = step.handoffs.winner ?: return false

            // A next step of the application that does not hear of it goes on as it would after any step
            val hearing = nextStep as? HandsOver ?: return false

            hearing.handedOver(to)
            return true
        }

        /** What the run left with [steps], which are all of them unless it says otherwise. */
        fun result(steps: List<Step> = this.steps) = RunResult(steps, resolved = resolved)

        /**
         * What the next step goes out with, set up with everything the run has said so far. It is part of the
         * invocation its setup says, or of [generation].
         */
        fun next(generation: GenAITelemetry.OpenSpan?): OutgoingStep {
            throwIfCancelled(options)

            val setup = nextStep.setUp(request.copy(messages = messages), steps.toList())

            return OutgoingStep(this@ToolLoop, setup, options, setup.invocation ?: generation)
        }

        /** The calls the application has to run. The ones the provider ran already came answered. */
        fun callsOf(response: ChatResponse) = response.toolCalls.filterNot { it.providerExecuted }

        fun finish(response: ChatResponse, step: OutgoingStep) =
            result(steps + Step(response, warnings = step.warnings, agent = step.agent))

        /**
         * Whether an answer without calls ends the run. It does, unless the step answers by calling an output tool
         * and the model has not been reminded of it yet.
         */
        fun endsWith(step: OutgoingStep) = step.outputTool == null || steps.lastOrNull()?.reminder != null

        /** Keeps the answer and tells the model to answer by calling the output tool, which takes one more step. */
        fun remind(response: ChatResponse, step: OutgoingStep) {
            throwIfOutOfSteps(response, step)

            val reminder = Message.user("Please include your response in a call to ${step.outputTool}.")

            steps.add(Step(response, reminder = reminder, warnings = step.warnings, agent = step.agent))
            messages = messages + response.asMessage(step.agent) + reminder
        }

        /**
         * Fails when the step that just answered was the last one allowed. It is asked before running its calls:
         * no model would read their results, and a tool with effects would have them all the same.
         */
        fun throwIfOutOfSteps(response: ChatResponse, step: OutgoingStep) {
            if (steps.size + 1 >= maxSteps) throw MaxStepsExceededError(maxSteps, finish(response, step))
        }

        /**
         * Fails when the steps are used up after running calls. Only a call to the output tool runs on the last step,
         * since it could end the run; this is when the model got it wrong and another step would be needed.
         */
        fun throwIfNoStepsLeft() {
            if (steps.size >= maxSteps) throw MaxStepsExceededError(maxSteps, result())
        }

        /** Keeps the step, with the agent its calls handed the conversation over to, and prepares the next one. */
        fun advance(response: ChatResponse, executions: List<ToolExecution>, step: OutgoingStep) {
            val results = executions.map { it.result }

            steps.add(
                Step(
                    response,
                    results,
                    executions.mapNotNull { it.failure },
                    handoff = step.handoffs.winner,
                    warnings = step.warnings,
                    agent = step.agent,
                    toolRuns = executions.mapNotNull { it.run?.let { run -> it.result.callId to run } }.toMap(),
                    pending = step.pending,
                )
            )
            messages = messages + response.asMessage(step.agent) + Message.Tool(results)
        }
    }

    /**
     * The run, step by step, as the events it goes through: [stream] hands them over as they happen and [run] reads
     * them all at once, so that both stop for the same reasons and send the same thing on the next call. Streaming,
     * the model answers as it writes and its parts are events too; otherwise it answers whole.
     *
     * A stream traces its own run: nothing of it is current between one event and the next, since that is the code of
     * whoever reads, so its spans hang from the one that was current when it was asked for, and are current only
     * around what runs at once, like the model opening its stream or a tool. Closing it ends the spans it left open,
     * since an iterator nobody reads again never gets to its end. [run] gives it the span of the generation instead,
     * which it ends itself.
     */
    private inner class Steps(
        request: ChatRequest,
        private val options: CallOptions,
        decisions: List<Decision>,
        private val streaming: Boolean,
        private var generation: GenAITelemetry.OpenSpan?,
    ): RunStream {
        private val run = Run(request, options, decisions)
        private val tracesGeneration = streaming && tracesItsRun
        private var current: ChatStream? = null
        private var closed = false

        private val parent = Context.current()
        private var chat: GenAITelemetry.ChatSpan? = null

        private val events = iterator {
            // The work starts with the first read, and so does the span of a stream
            if (tracesGeneration) generation = telemetry.openGeneration(parent)

            try {
                while (true) {
                    var step = run.next(generation)

                    if (run.resolving) {
                        val executions = mutableListOf<ToolExecution>()

                        for (call in run.waiting) {
                            val decision = run.decisionAbout(call)

                            if (decision is Approve) yield(RunEvent.ToolStarted(call))

                            val execution = step.resolve(call, decision).also { executions.add(it) }

                            if (decision is Approve) yield(RunEvent.ToolFinished(execution.result, execution.failure))
                            else yield(RunEvent.ToolNotApproved(execution.result))
                        }

                        run.resolved(executions, step)

                        if (run.handedOver(step)) {
                            val from = step.agent

                            step = run.next(generation)
                            yield(RunEvent.Handoff(from, step.agent!!))
                        }
                    }

                    yield(RunEvent.StepStarted(run.steps.size + 1))

                    val response = if (streaming) {
                        val span = telemetry.openChat(step.model, step.request, step.agent, step.invocation)
                            .also { chat = it }
                        val stream = span.opening { step.model.stream(step.request, options) }.also { current = it }
                        stream.use {
                            for (part in it) {
                                span.chunk()
                                yield(RunEvent.Model(part))
                            }
                            it.response()
                        }.also {
                            span.end(it)
                            current = null
                        }
                    } else {
                        telemetry.chat(step.model, step.request, step.agent, step.invocation) {
                            step.model.generate(step.request, options)
                        }
                    }

                    step.answered(response)

                    val calls = run.callsOf(response)
                    val number = run.steps.size + 1

                    if (calls.isEmpty()) {
                        if (run.endsWith(step)) {
                            finished(run.finish(response, step))
                            yield(RunEvent.StepFinished(number))
                            return@iterator
                        }

                        run.remind(response, step)
                        yield(RunEvent.StepFinished(number))
                        continue
                    }

                    if (!step.mayEndWith(calls)) run.throwIfOutOfSteps(response, step)

                    step.check(calls)

                    val executions = mutableListOf<ToolExecution>()
                    val running = step.toRun(calls)

                    if (step.runInParallel(running)) {
                        for (call in running) yield(RunEvent.ToolStarted(call))

                        executions.addAll(step.inParallel(running).map { step.finished(it) })

                        for (execution in executions) yield(RunEvent.ToolFinished(execution.result, execution.failure))
                    } else {
                        for (call in running) {
                            yield(RunEvent.ToolStarted(call))

                            val execution = step.finished(step.execute(call))

                            executions.add(execution)
                            yield(RunEvent.ToolFinished(execution.result, execution.failure))
                        }
                    }

                    for (pending in step.pending) yield(RunEvent.ApprovalRequested(pending))

                    run.advance(response, executions, step)
                    yield(RunEvent.StepFinished(number))

                    step.handoffs.winner?.let { yield(RunEvent.Handoff(step.agent, it)) }

                    if (step.paused || step.endedBy(executions)) {
                        finished(run.result())
                        return@iterator
                    }

                    run.throwIfNoStepsLeft()
                }
            } catch (e: Throwable) {
                chat?.fail(e)
                if (tracesGeneration) generation?.fail(e)
                throw e
            }
        }

        private var last: RunResult? = null

        private fun finished(result: RunResult) {
            last = result
            if (tracesGeneration) generation?.end(result.usage)
        }

        override fun hasNext() = !closed && events.hasNext()

        override fun next() = events.next()

        override fun result(): RunResult {
            while (hasNext()) next()

            return last ?: run.result()
        }

        override fun close() {
            closed = true
            current?.close()
            current = null
            // What already ended stays as it ended
            chat?.cut()
            if (tracesGeneration) generation?.cut()
        }
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val GENERIC_FAILURE = "Tool execution failed"

        /** What the model reads of a call a person did not approve, when the decision says nothing else. */
        const val NOT_APPROVED = "This call was not approved, so it did not run. Do not call it again: tell the user " +
            "it was not approved."
    }
}
