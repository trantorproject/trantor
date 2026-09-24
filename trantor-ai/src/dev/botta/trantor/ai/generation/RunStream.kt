package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.StreamPart

/**
 * A run being received. Like a `ChatStream`, it is a blocking pull iterator meant for `use {}`: reading blocks the
 * virtual thread and closing it cancels the call in flight, so nothing keeps running for an answer nobody reads.
 *
 * ```kotlin
 * ai.stream { user(question); tools(searchProducts) }.use { stream ->
 *     stream.textDeltas().forEach { print(it) }
 *     println(stream.result().usage)
 * }
 * ```
 */
interface RunStream: Iterator<RunEvent>, AutoCloseable {
    /** Everything the run left. Consumes what is left of the stream if it was not read. */
    fun result(): RunResult
}

/**
 * Only the text as it arrives, for whoever just wants to print the answer. It reads any stream of a run, a
 * generation's or the agents'.
 */
fun Iterator<RunEvent>.textDeltas() = asSequence()
    .filterIsInstance<RunEvent.Model>()
    .map { it.part }
    .filterIsInstance<StreamPart.TextDelta>()
    .map { it.text }
