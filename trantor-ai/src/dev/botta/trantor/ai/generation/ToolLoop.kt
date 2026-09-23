package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.primitives.logging.getLogger

/**
 * Calls the model, runs the tools it asks for and calls it again with their results, until it answers without
 * asking for more.
 *
 * It is the one tool loop of trantor-ai: a generation with tools and an agent both run on it.
 *
 * Each call carries the answer before it whole — reasoning, signatures and parts we do not model — because that is
 * what lets the provider pick up where it left off. Tools the provider ran on its side are not run again: their
 * results already came in the answer.
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
    private val model: ChatModel,
    private val tools: List<Tool<*>>,
    /** How many calls to the model a generation may take. Past them, [MaxStepsExceededError]. */
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val run: RunContext = RunContext(),
    /** Asked in order for a safe message about an exception of a tool; the first that answers wins. */
    private val errorHandlers: List<ToolErrorHandler> = emptyList(),
) {
    private val logger = getLogger()
    private val toolsByName = tools.associateBy { it.name }

    fun run(request: ChatRequest, options: CallOptions = CallOptions()): RunResult {
        val run = Run(request, options)

        while (true) {
            throwIfCancelled(options)

            val response = model.generate(run.next(), options)
            val calls = run.callsOf(response)

            if (calls.isEmpty()) return run.finish(response)

            run.throwIfOutOfSteps(response)
            run.advance(response, executeAll(calls, options))
        }
    }

    /**
     * The same loop, received as it happens. Every event is produced while the run is going, so whoever reads it
     * sees the text and the tools as they happen; closing it stops the run where it is.
     */
    fun stream(request: ChatRequest, options: CallOptions = CallOptions()): RunStream = Streamed(request, options)

    /**
     * The steps of a run and the rules that end it, shared by [run] and [stream] so that both stop for the same
     * reasons and send the same thing on the next call.
     */
    private inner class Run(private val request: ChatRequest, private val options: CallOptions) {
        val steps = mutableListOf<Step>()
        private var messages = request.messages
        private val specs = request.tools + tools.map { it.spec() }

        /** The request of the next step, with everything the run has said so far. */
        fun next(): ChatRequest {
            throwIfCancelled(options)

            return request.copy(messages = messages, tools = specs)
        }

        /** The calls the application has to run. The ones the provider ran already came answered. */
        fun callsOf(response: ChatResponse) = response.toolCalls.filterNot { it.providerExecuted }

        fun finish(response: ChatResponse) = RunResult(steps + Step(response))

        /**
         * Fails when the step that just answered was the last one allowed. It is asked before running its calls:
         * no model would read their results, and a tool with effects would have them all the same.
         */
        fun throwIfOutOfSteps(response: ChatResponse) {
            if (steps.size + 1 >= maxSteps) throw MaxStepsExceededError(maxSteps, finish(response))
        }

        /** Keeps the step and prepares the next one. */
        fun advance(response: ChatResponse, executions: List<Execution>) {
            val results = executions.map { it.result }

            steps.add(Step(response, results, executions.mapNotNull { it.failure }))
            messages = messages + response.asMessage() + Message.Tool(results)
        }
    }

    private inner class Streamed(request: ChatRequest, private val options: CallOptions): RunStream {
        private val run = Run(request, options)
        private var current: ChatStream? = null
        private var closed = false

        private val events = iterator {
            while (true) {
                val request = run.next()

                yield(RunEvent.StepStarted(run.steps.size + 1))

                val stream = model.stream(request, options).also { current = it }
                val response = stream.use {
                    for (part in it) yield(RunEvent.Model(part))
                    it.response()
                }

                current = null

                val calls = run.callsOf(response)

                if (calls.isNotEmpty()) run.throwIfOutOfSteps(response)

                val executions = mutableListOf<Execution>()

                if (runInParallel(calls)) {
                    for (call in calls) yield(RunEvent.ToolStarted(call))

                    executions.addAll(inParallel(calls, options))

                    for (execution in executions) yield(RunEvent.ToolFinished(execution.result, execution.failure))
                } else {
                    for (call in calls) {
                        yield(RunEvent.ToolStarted(call))

                        val execution = execute(call)

                        executions.add(execution)
                        yield(RunEvent.ToolFinished(execution.result, execution.failure))
                    }
                }

                val number = run.steps.size + 1

                if (calls.isEmpty()) {
                    last = run.finish(response)
                    yield(RunEvent.StepFinished(number))
                    return@iterator
                }

                run.advance(response, executions)
                yield(RunEvent.StepFinished(number))
            }
        }

        private var last: RunResult? = null

        override fun hasNext() = !closed && events.hasNext()

        override fun next() = events.next()

        override fun result(): RunResult {
            while (hasNext()) next()

            return last ?: RunResult(run.steps)
        }

        override fun close() {
            closed = true
            current?.close()
            current = null
        }
    }

    /**
     * The calls of a step: at the same time when every tool of the step only reads, and one after the other when
     * any of them can write. Two calls that write could step on each other, and in order they happen the way the
     * model asked for them.
     */
    private fun executeAll(calls: List<ToolCallPart>, options: CallOptions): List<Execution> =
        if (runInParallel(calls)) inParallel(calls, options) else calls.map { execute(it) }

    private fun runInParallel(calls: List<ToolCallPart>) =
        calls.size > 1 && calls.all { toolsByName[it.toolName]?.readOnly == true }

    /**
     * Each call on its own virtual thread. They all finish before the step goes on, even when one of them fails,
     * and the results come back in the order of the calls and not in the order they answered. A cancellation, from
     * outside or from one of the tools, interrupts every one of them.
     */
    private fun inParallel(calls: List<ToolCallPart>, options: CallOptions): List<Execution> {
        val running = calls.map { Parallel(it, options) }

        options.cancellation?.onCancel { running.forEach { it.interrupt() } }.use {
            running.forEach { it.start() }
            joinAll(running)
        }

        return running.map { it.result() }
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
    private inner class Parallel(private val call: ToolCallPart, private val options: CallOptions) {
        private var execution: Execution? = null
        private var error: Throwable? = null

        private val thread = Thread.ofVirtual().name("tool:${call.toolName}").unstarted {
            try {
                // It may have been cancelled between the moment the step started and the moment this one did
                throwIfCancelled(options)
                execution = execute(call)
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

    private fun execute(call: ToolCallPart): Execution {
        val tool = toolsByName[call.toolName] ?: return unknown(call).let { failed(call, it, it.message!!) }

        return try {
            val result = tool.call(call.input, ToolContext(call.callId, call.toolName, run))
            Execution(ToolResultPart(call.callId, call.toolName, result.output))
        } catch (e: CancelledError) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancelledError("The thread was interrupted while ${call.toolName} ran", e)
        } catch (e: InvalidToolInputError) {
            failed(call, e, e.message!!)
        } catch (e: ToolError) {
            failed(call, e, e.message!!)
        } catch (e: Exception) {
            if (tool.onError == ToolErrorModes.FailRun) throw e

            logger.error("Tool ${call.toolName} failed on call ${call.callId}: ${e.message}", e)
            failed(call, e, errorHandlers.firstNotNullOfOrNull { it.handle(e, call) } ?: GENERIC_FAILURE)
        }
    }

    private fun unknown(call: ToolCallPart) = ToolError(
        "There is no tool called ${call.toolName}. The tools are: ${toolsByName.keys.joinToString()}",
    )

    private fun failed(call: ToolCallPart, error: Throwable, message: String) = Execution(
        ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true),
        ToolFailure(call.callId, call.toolName, error),
    )

    class Execution(val result: ToolResultPart, val failure: ToolFailure? = null)

    private fun throwIfCancelled(options: CallOptions) {
        options.cancellation?.throwIfCancelled()

        if (Thread.currentThread().isInterrupted) throw CancelledError("The thread was interrupted")
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val GENERIC_FAILURE = "Tool execution failed"
    }
}
