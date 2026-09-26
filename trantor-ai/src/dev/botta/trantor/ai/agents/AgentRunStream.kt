package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.RunStream

/**
 * A run of the agents being received: the same events as a generation's [RunStream], with a
 * [RunEvent.Handoff] between the step that handed the conversation over and the first one of the other agent. Like
 * it, a blocking pull iterator meant for `use {}`, and closing it cancels the call in flight, so the agent after a
 * handoff does not run if nobody reads it. [AgentHooks.afterRun] is called once it is read to its end, and not when
 * it is closed before.
 *
 * ```kotlin
 * agents.stream(support, Message.user(text)) { team(sales) }.use { stream ->
 *     stream.textDeltas().forEach { print(it) }
 *     println(stream.result().lastAgent.name)
 * }
 * ```
 *
 * The guardrails show in it:
 *
 * - The input guardrails run on the first read, so whoever reads it learns of a trip the same way as of any other.
 * - When a guardrail stops the run, the last event is a [RunEvent.GuardrailTripped], and reading on throws the
 *   [GuardrailTrippedError].
 * - With an output guardrail that [holds the text][OutputGuardrail.holdsText], what the model produces in a step is
 *   held back until the step shows whether it is the last one. A step that calls tools is not, and what it held comes
 *   out before its first tool starts. The last one comes out all together once the output guardrails passed, and not
 *   at all if one of them tripped.
 */
class AgentRunStream internal constructor(
    private val stream: RunStream,
    private val run: StreamedRun,
): Iterator<RunEvent>, AutoCloseable {
    private var result: AgentRunResult? = null
    private var closed = false

    private val events = iterator {
        try {
            run.checkInput()

            val held = mutableListOf<RunEvent>()
            var holding = false

            for (event in stream) {
                when {
                    event is RunEvent.StepStarted -> {
                        holding = run.holdsText()
                        yield(event)
                    }

                    !holding -> yield(event)

                    event is RunEvent.Model -> held.add(event)

                    // The model asked for tools, so this step is not the last and what it said can go
                    event is RunEvent.ToolStarted -> {
                        holding = false
                        yieldAll(held.toList())
                        held.clear()
                        yield(event)
                    }

                    // Without tools a step ends the run, unless it only got a reminder to call the output tool
                    event is RunEvent.StepFinished -> {
                        if (!stream.hasNext()) finished()

                        yieldAll(held.toList())
                        held.clear()
                        yield(event)
                    }

                    else -> yield(event)
                }
            }

            run.afterRun(finished())
        } catch (e: GuardrailTrippedError) {
            yield(RunEvent.GuardrailTripped(e.guardrail, e.reason))
            throw e
        }
    }

    override fun hasNext() = !closed && events.hasNext()

    override fun next() = events.next()

    /** Everything the run left, with the agent of each step. Consumes what is left of the stream if it was not read. */
    fun result(): AgentRunResult {
        while (hasNext()) next()

        return result ?: run.resultOf(stream.result())
    }

    override fun close() {
        closed = true
        stream.close()
    }

    /** The result of the run once it ended, checked by the output guardrails the first time it is asked for. */
    private fun finished() = result ?: run.resultOf(stream.result()).also {
        run.checkOutput(it)
        result = it
    }
}

/** What the stream needs of the run it reads, which the [AgentRunner] knows. */
internal interface StreamedRun {
    /** Throws [GuardrailTrippedError] when an input guardrail trips. */
    fun checkInput()

    /** Whether the answer of the step going out is held back until the output guardrails pass. */
    fun holdsText(): Boolean

    fun resultOf(result: RunResult): AgentRunResult

    /** Throws [GuardrailTrippedError] when an output guardrail trips. */
    fun checkOutput(result: AgentRunResult)

    fun afterRun(result: AgentRunResult)
}
