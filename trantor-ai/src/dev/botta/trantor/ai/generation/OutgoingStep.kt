package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.generation.ToolLoop.Companion.GENERIC_FAILURE
import dev.botta.trantor.ai.generation.ToolLoop.Companion.NOT_APPROVED
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.telemetry.GenAITelemetry
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.tools.search.SearchToolsTool
import dev.botta.trantor.primitives.ContextPropagation
import dev.botta.trantor.primitives.logging.getLogger

/**
 * A step about to go out: its model, the request with what the model is told about the tools, and the tools that
 * answer its calls. The calls of an answer run with the tools of the step that got it, even when the next step goes
 * out with others.
 */
internal class OutgoingStep(
    private val loop: ToolLoop,
    private val setup: StepSetup,
    private val options: CallOptions,
    /** The invocation the step is part of, which its spans hang from and its calls count in. */
    val invocation: GenAITelemetry.OpenSpan?,
) {
    private val logger = getLogger()
    val model = setup.model
    val outputTool = setup.outputTool
    val agent = setup.agent
    val handoffs = Handoffs(setup.team)
    private val hooks = setup.hooks ?: NoStepHooks

    /** The tools of the step: the ones it always has, and with some to search for, the search and those found. */
    private val tools = setup.tools + searching(setup)
    private val toolsByName = tools.associateBy { it.name }

    /** The calls of the step that will not run, by id, with what the model reads instead. */
    private var refusals = emptyMap<String, ToolRefusal>()

    /** What the step sends of the conversation, with the results of the calls the run resolved before it. */
    private var messages = setup.request.messages

    /**
     * The messages of the step with every call and its result together, as the providers take them. Worked out when
     * the step goes out, once the calls the run resolved before it have their results.
     */
    private val paired by lazy { ToolPairs.matched(messages) }

    // Asked again on every step, so that a description that depends on the moment is up to date; and built when the
    // step goes out, so that the hooks see the results of the calls the run resolved before it
    val request by lazy {
        // Every tool the step may come to have, the ones to search for included, so that a clash fails before they
        // are found
        val search = if (setup.searchable.isEmpty()) emptyList() else listOf(SearchToolsTool.NAME)
        failOnDuplicates(
            setup.request.tools.map { it.name } + setup.tools.map { it.name } + setup.searchable.map { it.name } +
                search,
        )
        val loads = providerLoads(setup)
        val deferred = if (loads) setup.searchable.map { it.name }.toSet() else emptySet()
        val tools = setup.request.tools + tools.map { tool ->
            val spec = tool.spec(loop.serializer)
            when {
                tool.name in deferred -> spec.copy(deferLoading = true)
                tool is SearchToolsTool && loads -> spec.copy(searchesTools = true)
                else -> spec
            }
        }

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
     * Asks about every call before any of them runs, so that a check that fails the run leaves no call of the step
     * half done. A call the check refused is answered as refused and does not wait for approval: there is nothing to
     * approve. One the check asked approval for waits with its reason, and its tool is not asked.
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
     * Answers a call the conversation left waiting for approval, before the step goes out: an approved one runs with
     * the tools and the hooks of the step, without asking for approval again; any other one is answered as not
     * approved, with the message of the decision or [NOT_APPROVED].
     */
    fun resolve(call: ToolCallPart, decision: Decision?): ToolExecution = when (decision) {
        is Approve -> finished(execute(call))
        is Reject -> refused(call, decision.message ?: NOT_APPROVED)
        null -> refused(call, NOT_APPROVED)
    }

    /** Sends the results of the calls the run resolved right after the answer that made them. */
    fun resolved(calls: ResolvedCalls) {
        messages = calls.into(messages)
    }

    /** A call that ran, with its handoff settled, as the model will read it. */
    fun finished(execution: ToolExecution) =
        handoffs.settle(execution, paused).also { hooks.afterTool(it.result, it.failure) }

    /** Whether the answer calls the output tool, which ends the run if the call is right. */
    fun mayEndWith(calls: List<ToolCallPart>) = calls.any { it.toolName == outputTool }

    fun endedBy(executions: List<ToolExecution>) =
        executions.any { it.result.toolName == outputTool && !it.result.isError }

    /**
     * Whether the calls run at the same time: when every tool of the step only reads. Two calls that write could
     * step on each other, and one after the other they happen the way the model asked for them.
     */
    fun runInParallel(calls: List<ToolCallPart>) =
        calls.size > 1 && calls.all { toolsByName[it.toolName]?.readOnly == true }

    /**
     * Each call on its own virtual thread. They all finish before the step goes on, even when one of them fails, and
     * the results come back in the order of the calls and not in the order they answered. A cancellation, from
     * outside or from one of the tools, interrupts every one of them.
     */
    fun inParallel(calls: List<ToolCallPart>): List<ToolExecution> {
        val running = calls.map { Parallel(it) }

        options.cancellation?.onCancel { running.forEach { it.interrupt() } }.use {
            running.forEach { it.start() }
            joinAll(running)
        }

        return running.map { it.result() }
    }

    fun execute(call: ToolCallPart): ToolExecution {
        refusals[call.callId]?.let { return refused(call, it.message) }

        return loop.telemetry.tool(call, toolsByName[call.toolName], agent, invocation) { run(call) }
    }

    private fun run(call: ToolCallPart): ToolExecution {
        // Out of the try: a hook that fails is a failure of the application, not of the tool
        val input = hooks.beforeTool(call)
        val tool = toolsByName[call.toolName] ?: return unknown(call).let { failed(call, it, it.message!!, input) }

        return try {
            val result = tool.call(input, contextOf(call))
            ToolExecution(
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
            // A call the cancellation of the run cut fails on its way, like the HTTP client of Trantor does: the tool
            // did not fail, the run was cancelled
            throwIfCancelled(options)
            if (tool.onError == ToolErrorModes.FailRun) throw e

            logger.error("Tool ${call.toolName} failed on call ${call.callId}: ${e.message}", e)
            val message = loop.errorHandlers.firstNotNullOfOrNull { it.handle(e, call) } ?: GENERIC_FAILURE
            failed(call, e, message, input)
        }
    }

    private fun failOnDuplicates(names: List<String>) {
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        if (duplicates.isEmpty()) return

        val names = duplicates.joinToString()
        throw DuplicateToolError(duplicates, "More than one tool is called $names: the model could not say which")
    }

    private fun contextOf(call: ToolCallPart) =
        setup.toolContext?.invoke(call) ?: ToolContext(call.callId, call.toolName, loop.run, options, loop.serializer)

    private fun unknown(call: ToolCallPart) = ToolError(
        "There is no tool called ${call.toolName}. The tools are: ${toolsByName.keys.joinToString()}",
    )

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

    /** One call of the step on its own virtual thread, holding what it left: its execution or the error it ended in. */
    private inner class Parallel(private val call: ToolCallPart) {
        private var execution: ToolExecution? = null
        private var error: Throwable? = null

        // What the thread of the step knows, so the tool logs and traces as part of the run
        private val context = ContextPropagation.capture()

        private val thread = Thread.ofVirtual().name("tool:${call.toolName}").unstarted {
            try {
                // It may have been cancelled between the moment the step started and the moment this one did
                throwIfCancelled(options)
                execution = ContextPropagation.runWithContext(context) { execute(call) }
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
        fun result(): ToolExecution {
            error?.let { throw it }

            return execution!!
        }
    }
}
