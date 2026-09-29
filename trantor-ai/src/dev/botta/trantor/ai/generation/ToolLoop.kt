package dev.botta.trantor.ai.generation

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.errors.NestedApprovalError
import dev.botta.trantor.ai.errors.NoPendingCallError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.throwIfCancelled
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.ai.telemetry.AITelemetrySettings
import dev.botta.trantor.ai.telemetry.GenAITelemetry
import dev.botta.trantor.primitives.ContextPropagation
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
    private val run: RunContext = RunContext(),
    /** Asked in order for a safe message about an exception of a tool; the first that answers wins. */
    private val errorHandlers: List<ToolErrorHandler> = emptyList(),
    /**
     * What traces the run: an `invoke_agent` span with a `chat` span for each call to the model and an `execute_tool`
     * span for each tool. Without an SDK behind it, it costs nothing.
     */
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
    /** Whether those spans carry what was said, which they do not unless asked. */
    telemetrySettings: AITelemetrySettings = AITelemetrySettings(),
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
    ): this(NextStep.fixed(model, tools), maxSteps, run, errorHandlers, openTelemetry, telemetrySettings)

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
        tracesItsRun: Boolean,
    ): this(nextStep, maxSteps, run, errorHandlers, openTelemetry, telemetrySettings) {
        this.tracesItsRun = tracesItsRun
    }

    private val logger = getLogger()
    private val telemetry = GenAITelemetry(openTelemetry, telemetrySettings)
    private var tracesItsRun = true

    /**
     * Runs [request] to the answer. [decisions] say what a person decided about the calls its conversation left
     * waiting for approval, which the run answers before it calls the model. See [Decision].
     */
    fun run(request: ChatRequest, options: CallOptions = CallOptions(), decisions: List<Decision> = emptyList()) =
        if (tracesItsRun) telemetry.generation { loop(request, options, decisions, it) }
        else loop(request, options, decisions, null)

    private fun loop(
        request: ChatRequest,
        options: CallOptions,
        decisions: List<Decision>,
        generation: GenAITelemetry.OpenSpan?,
    ): RunResult {
        val run = Run(request, options, decisions)

        while (true) {
            throwIfCancelled(options)

            var step = run.next(generation)

            if (run.resolving) {
                run.resolved(run.waiting.map { step.resolve(it, run.decisionAbout(it)) }, step)

                if (run.handedOver(step)) step = run.next(generation)
            }

            val response = telemetry.chat(step.model, step.request, step.agent, step.invocation) {
                step.model.generate(step.request, options)
            }.also { step.answered(it) }
            val calls = run.callsOf(response)

            if (calls.isEmpty()) {
                if (run.endsWith(step)) return run.finish(response, step)

                run.remind(response, step)
                continue
            }

            if (!step.mayEndWith(calls)) run.throwIfOutOfSteps(response, step)

            step.check(calls)

            val executions = step.executeAll(step.toRun(calls)).map { step.finished(it) }

            run.advance(response, executions, step)

            if (step.paused || step.endedBy(executions)) return run.result()

            run.throwIfNoStepsLeft()
        }
    }

    /**
     * The same loop, received as it happens. Every event is produced while the run is going, so whoever reads it
     * sees the text and the tools as they happen; closing it stops the run where it is.
     */
    fun stream(
        request: ChatRequest,
        options: CallOptions = CallOptions(),
        decisions: List<Decision> = emptyList(),
    ): RunStream = Streamed(request, options, decisions)

    /**
     * The steps of a run and the rules that end it, shared by [run] and [stream] so that both stop for the same
     * reasons and send the same thing on the next call.
     */
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
        fun resolved(executions: List<Execution>, step: Outgoing) {
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
        fun handedOver(step: Outgoing): Boolean {
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
        fun next(generation: GenAITelemetry.OpenSpan?): Outgoing {
            throwIfCancelled(options)

            val setup = nextStep.setUp(request.copy(messages = messages), steps.toList())

            return Outgoing(setup, options, setup.invocation ?: generation)
        }

        /** The calls the application has to run. The ones the provider ran already came answered. */
        fun callsOf(response: ChatResponse) = response.toolCalls.filterNot { it.providerExecuted }

        fun finish(response: ChatResponse, step: Outgoing) =
            result(steps + Step(response, warnings = step.warnings, agent = step.agent))

        /**
         * Whether an answer without calls ends the run. It does, unless the step answers by calling an output tool
         * and the model has not been reminded of it yet.
         */
        fun endsWith(step: Outgoing) = step.outputTool == null || steps.lastOrNull()?.reminder != null

        /** Keeps the answer and tells the model to answer by calling the output tool, which takes one more step. */
        fun remind(response: ChatResponse, step: Outgoing) {
            throwIfOutOfSteps(response, step)

            val reminder = Message.user("Please include your response in a call to ${step.outputTool}.")

            steps.add(Step(response, reminder = reminder, warnings = step.warnings, agent = step.agent))
            messages = messages + response.asMessage(step.agent) + reminder
        }

        /**
         * Fails when the step that just answered was the last one allowed. It is asked before running its calls:
         * no model would read their results, and a tool with effects would have them all the same.
         */
        fun throwIfOutOfSteps(response: ChatResponse, step: Outgoing) {
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
        fun advance(response: ChatResponse, executions: List<Execution>, step: Outgoing) {
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
     * The loop as it is read. Nothing of it is current between one event and the next, since that is the code of
     * whoever reads: its spans hang from the one that was current when the stream was asked for, and are current only
     * around what runs at once, like the model opening its stream or a tool. Closing it ends the spans it left open,
     * since an iterator nobody reads again never gets to its end.
     */
    private inner class Streamed(
        request: ChatRequest,
        private val options: CallOptions,
        decisions: List<Decision>,
    ): RunStream {
        private val run = Run(request, options, decisions)
        private var current: ChatStream? = null
        private var closed = false

        private val parent = Context.current()
        private var generation: GenAITelemetry.OpenSpan? = null
        private var chat: GenAITelemetry.ChatSpan? = null

        private val events = iterator {
            // The work starts with the first read, and so does the span of the run
            if (tracesItsRun) generation = telemetry.openGeneration(parent)

            try {
                while (true) {
                    var step = run.next(generation)

                    if (run.resolving) {
                        val executions = mutableListOf<Execution>()

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

                    val chat = telemetry.openChat(step.model, step.request, step.agent, step.invocation)
                        .also { chat = it }
                    val stream = chat.opening { step.model.stream(step.request, options) }.also { current = it }
                    val response = stream.use {
                        for (part in it) {
                            chat.chunk()
                            yield(RunEvent.Model(part))
                        }
                        it.response()
                    }

                    chat.end(response)
                    current = null
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

                    val executions = mutableListOf<Execution>()
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
                generation?.fail(e)
                throw e
            }
        }

        private var last: RunResult? = null

        private fun finished(result: RunResult) {
            last = result
            generation?.end(result.usage)
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
            generation?.cut()
        }
    }

    /**
     * A step about to go out: its model, the request with what the model is told about the tools, and the tools
     * that answer its calls. The calls of an answer run with the tools of the step that got it, even when the next
     * step goes out with others.
     */
    private inner class Outgoing(
        private val setup: StepSetup,
        private val options: CallOptions,
        /** The invocation the step is part of, which its spans hang from and its calls count in. */
        val invocation: GenAITelemetry.OpenSpan?,
    ) {
        val model = setup.model
        val outputTool = setup.outputTool
        val agent = setup.agent
        val handoffs = Handoffs(setup.team)
        private val hooks = setup.hooks ?: NoStepHooks
        private val toolsByName = setup.tools.associateBy { it.name }

        /** The calls of the step that will not run, by id, with what the model reads instead. */
        private var refusals = emptyMap<String, ToolRefusal>()

        /** What the step sends of the conversation, with the results of the calls the run resolved before it. */
        private var messages = setup.request.messages

        /**
         * The messages of the step with every call and its result together, as the providers take them. Worked out
         * when the step goes out, once the calls the run resolved before it have their results.
         */
        private val paired by lazy { ToolPairs.matched(messages) }

        // Asked again on every step, so that a description that depends on the moment is up to date; and built when
        // the step goes out, so that the hooks see the results of the calls the run resolved before it
        val request by lazy {
            val tools = setup.request.tools + setup.tools.map { it.spec() }
            failOnDuplicates(tools)

            hooks.beforeModel(setup.request.copy(messages = paired.first, tools = tools))
        }

        /** What the run noticed in the step: the halves of a pair it did not send, its handoffs, the calls not run. */
        val warnings get() = paired.second + handoffs.warnings + refusals.values.map { ModelWarning(it.warning) }

        fun answered(response: ChatResponse) = hooks.afterModel(response)

        /** The calls of the step that wait for a person to approve them. They do not run, and the run ends paused. */
        var pending = emptyList<PendingCall>()
            private set

        val paused get() = pending.isNotEmpty()

        /**
         * Asks about every call before any of them runs, so that a check that fails the run leaves no call of the
         * step half done. A call the check refused is answered as refused and does not wait for approval: there is
         * nothing to approve. One the check asked approval for waits with its reason, and its tool is not asked.
         */
        fun check(calls: List<ToolCallPart>) {
            val checks = calls.associate { it.callId to hooks.checkTool(it) }

            refusals = checks.mapNotNull { (callId, check) -> (check as? ToolRefusal)?.let { callId to it } }.toMap()
            pending = calls.mapNotNull { call ->
                when (val check = checks[call.callId]) {
                    is ToolRefusal -> null
                    is ToolApproval -> PendingCall(call, agent, check.reason)
                    null -> PendingCall(call, agent).takeIf { asksForApproval(call) }
                }
            }
        }

        private fun asksForApproval(call: ToolCallPart) =
            toolsByName[call.toolName]?.asksForApproval(call.input, contextOf(call)) == true

        /** The calls that run in the step: all of them but the ones waiting for approval. */
        fun toRun(calls: List<ToolCallPart>) = calls.filter { call -> pending.none { it.call.callId == call.callId } }

        /**
         * Answers a call the conversation left waiting for approval, before the step goes out: an approved one runs
         * with the tools and the hooks of the step, without asking for approval again; any other one is answered as
         * not approved, with the message of the decision or [NOT_APPROVED].
         */
        fun resolve(call: ToolCallPart, decision: Decision?): Execution = when (decision) {
            is Approve -> finished(execute(call))
            is Reject -> refused(call, decision.message ?: NOT_APPROVED)
            null -> refused(call, NOT_APPROVED)
        }

        /** Sends the results of the calls the run resolved right after the answer that made them. */
        fun resolved(calls: ResolvedCalls) {
            messages = calls.into(messages)
        }

        /** A call that ran, with its handoff settled, as the model will read it. */
        fun finished(execution: Execution) =
            handoffs.settle(execution, paused).also { hooks.afterTool(it.result, it.failure) }

        /** Whether the answer calls the output tool, which ends the run if the call is right. */
        fun mayEndWith(calls: List<ToolCallPart>) = calls.any { it.toolName == outputTool }

        fun endedBy(executions: List<Execution>) =
            executions.any { it.result.toolName == outputTool && !it.result.isError }

        /**
         * The calls of the step: at the same time when every tool of the step only reads, and one after the other
         * when any of them can write. Two calls that write could step on each other, and in order they happen the
         * way the model asked for them.
         */
        fun executeAll(calls: List<ToolCallPart>): List<Execution> =
            if (runInParallel(calls)) inParallel(calls) else calls.map { execute(it) }

        fun runInParallel(calls: List<ToolCallPart>) =
            calls.size > 1 && calls.all { toolsByName[it.toolName]?.readOnly == true }

        /**
         * Each call on its own virtual thread. They all finish before the step goes on, even when one of them
         * fails, and the results come back in the order of the calls and not in the order they answered. A
         * cancellation, from outside or from one of the tools, interrupts every one of them.
         */
        fun inParallel(calls: List<ToolCallPart>): List<Execution> {
            val running = calls.map { Parallel(it, this, options) }

            options.cancellation?.onCancel { running.forEach { it.interrupt() } }.use {
                running.forEach { it.start() }
                joinAll(running)
            }

            return running.map { it.result() }
        }

        fun execute(call: ToolCallPart): Execution {
            refusals[call.callId]?.let { return refused(call, it) }

            return telemetry.tool(call, toolsByName[call.toolName], agent, invocation) { run(call) }
        }

        private fun run(call: ToolCallPart): Execution {
            // Out of the try: a hook that fails is a failure of the application, not of the tool
            val input = hooks.beforeTool(call)
            val tool = toolsByName[call.toolName] ?: return unknown(call).let { failed(call, it, it.message!!, input) }

            return try {
                val result = tool.call(input, contextOf(call))
                Execution(
                    ToolResultPart(call.callId, call.toolName, result.output),
                    handoff = result.handoff,
                    run = result.run,
                    input = input,
                )
            } catch (e: CancelledError) {
                throw e
            } catch (e: NestedApprovalError) {
                // A mistake of the application, which the model could not work around
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancelledError("The thread was interrupted while ${call.toolName} ran", e)
            } catch (e: InvalidToolInputError) {
                failed(call, e, e.message!!, input)
            } catch (e: ToolError) {
                failed(call, e, e.message!!, input)
            } catch (e: Exception) {
                // A call the cancellation of the run cut fails on its way, like the HTTP client of Trantor does: the
                // tool did not fail, the run was cancelled
                throwIfCancelled(options)
                if (tool.onError == ToolErrorModes.FailRun) throw e

                logger.error("Tool ${call.toolName} failed on call ${call.callId}: ${e.message}", e)
                failed(call, e, errorHandlers.firstNotNullOfOrNull { it.handle(e, call) } ?: GENERIC_FAILURE, input)
            }
        }

        private fun failOnDuplicates(tools: List<ToolSpec>) {
            val duplicates = tools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.toList()
            if (duplicates.isEmpty()) return

            val names = duplicates.joinToString()
            throw DuplicateToolError(duplicates, "More than one tool is called $names: the model could not say which")
        }

        private fun contextOf(call: ToolCallPart) =
            setup.toolContext?.invoke(call) ?: ToolContext(call.callId, call.toolName, run, options)

        private fun unknown(call: ToolCallPart) = ToolError(
            "There is no tool called ${call.toolName}. The tools are: ${toolsByName.keys.joinToString()}",
        )
    }

    /** Waits for every one of them, and if the run itself is interrupted while it waits, stops them all. */
    private fun joinAll(running: List<Parallel>) {
        try {
            running.forEach { it.join() }
        } catch (e: InterruptedException) {
            running.forEach { it.interrupt() }
            running.forEach { it.joinInterrupted() }

            Thread.currentThread().interrupt()
            throw CancelledError("The thread was interrupted while the tools of the step ran", e)
        }
    }

    /** One call of a step on its own virtual thread, holding what it left: its execution or the error that ended it. */
    private inner class Parallel(
        private val call: ToolCallPart,
        private val step: Outgoing,
        private val options: CallOptions,
    ) {
        private var execution: Execution? = null
        private var error: Throwable? = null

        // What the thread of the step knows, so the tool logs and traces as part of the run
        private val context = ContextPropagation.capture()

        private val thread = Thread.ofVirtual().name("tool:${call.toolName}").unstarted {
            try {
                // It may have been cancelled between the moment the step started and the moment this one did
                throwIfCancelled(options)
                execution = ContextPropagation.runWithContext(context) { step.execute(call) }
            } catch (e: Throwable) {
                error = e
            }
        }

        fun start() = thread.start()

        fun interrupt() = thread.interrupt()

        fun join() = thread.join()

        /** Waits for one that was told to stop, without minding another interruption. */
        fun joinInterrupted() {
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** What the call left. The first error in the order of the calls is the one that ends the run. */
        fun result(): Execution {
            error?.let { throw it }

            return execution!!
        }
    }

    // Nothing failed: the call did not run, and the model reads why
    private fun refused(call: ToolCallPart, refusal: ToolRefusal) = refused(call, refusal.message)

    private fun refused(call: ToolCallPart, message: String) =
        Execution(ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true))

    private fun failed(call: ToolCallPart, error: Throwable, message: String, input: JsonObject? = null) = Execution(
        ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true),
        ToolFailure(call.callId, call.toolName, error),
        input = input,
    )

    class Execution(
        val result: ToolResultPart,
        val failure: ToolFailure? = null,
        /** The agent the tool handed the conversation over to, before the step settles it. */
        val handoff: String? = null,
        /** The run of a model the tool made to answer. */
        val run: RunResult? = null,
        /** The args the tool ran with, after the hooks. Null when it did not get that far. */
        val input: JsonObject? = null,
    )

    /**
     * The handoffs of one step, settled in the order of the calls as they finish. The conversation changes hands
     * once the step is over, so every call of the step still runs with the tools that asked for it.
     *
     * - The first handoff to an agent of the team wins.
     * - A later one in the same step is answered as an error to the model — its tool did run, with its effects — and
     *   leaves a warning, since the model asked for two things at once and only one could happen.
     * - One to an agent outside the team is answered as an error naming the team, so the model can pick again.
     * - Without a team, which is a generation, there is nobody to hand over to: it is left with a warning.
     * - In a step that pauses for approval, it is answered as an error, with a warning: the run that picks the
     *   conversation up goes on with the agent that made the calls that wait, and could not tell it had been handed
     *   over. The model can hand it over again then.
     */
    private class Handoffs(private val team: Set<String>?) {
        /** The agent the conversation goes to after the step. */
        var winner: String? = null
            private set
        private var winnerTool: String? = null
        val warnings = mutableListOf<ModelWarning>()

        fun settle(execution: Execution, paused: Boolean): Execution {
            val to = execution.handoff ?: return execution
            val tool = execution.result.toolName

            if (team == null) {
                warnings.add(
                    ModelWarning(
                        "$tool handed the conversation over to $to, but a generation has no agents to hand it to; " +
                            "it was ignored",
                    )
                )
                return execution
            }

            if (to !in team) return refused(execution, "There is no agent called $to. The team is: ${team.joinToString()}")

            if (paused) {
                warnings.add(
                    ModelWarning(
                        "$tool handed the conversation over to $to in a step that paused for approval, so it was not " +
                            "handed over",
                    ),
                )
                return refused(
                    execution,
                    "The conversation was not handed over to $to, since other calls of this step wait for approval. " +
                        "Hand it over again once they are answered.",
                )
            }

            val first = winner ?: return execution.also {
                winner = to
                winnerTool = tool
            }

            warnings.add(
                ModelWarning(
                    "$tool handed the conversation over to $to after $winnerTool had handed it over to $first in " +
                        "the same step; the first one won",
                )
            )
            return refused(execution, "The conversation was already handed over to $first, so this handoff to $to was ignored")
        }

        private fun refused(execution: Execution, message: String) = Execution(
            execution.result.copy(output = ToolOutput.Text(message), isError = true),
            execution.failure,
            run = execution.run,
        )
    }

    private fun throwIfCancelled(options: CallOptions) {
        options.cancellation?.throwIfCancelled()

        if (Thread.currentThread().isInterrupted) throw CancelledError("The thread was interrupted")
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val GENERIC_FAILURE = "Tool execution failed"

        /** What the model reads of a call a person did not approve, when the decision says nothing else. */
        const val NOT_APPROVED = "This call was not approved, so it did not run. Do not call it again: tell the user " +
            "it was not approved."
    }
}
