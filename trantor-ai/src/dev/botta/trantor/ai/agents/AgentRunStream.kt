package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.RunStream

/**
 * A run of the agents being received: the same events as a generation's [RunStream], with a
 * [RunEvent.Handoff] between the step that handed the conversation over and the first one of the other agent. Like
 * it, a blocking pull iterator meant for `use {}`, and closing it cancels the call in flight, so the agent after a
 * handoff does not run if nobody reads it.
 *
 * ```kotlin
 * agents.stream(support, Message.user(text)) { team(sales) }.use { stream ->
 *     stream.textDeltas().forEach { print(it) }
 *     println(stream.result().lastAgent.name)
 * }
 * ```
 */
class AgentRunStream internal constructor(
    private val stream: RunStream,
    private val resultOf: (RunResult) -> AgentRunResult,
): Iterator<RunEvent> by stream, AutoCloseable {
    /** Everything the run left, with the agent of each step. Consumes what is left of the stream if it was not read. */
    fun result() = resultOf(stream.result())

    override fun close() = stream.close()
}
