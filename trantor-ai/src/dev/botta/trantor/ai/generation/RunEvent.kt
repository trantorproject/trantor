package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.models.chat.StreamPart
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart

/**
 * What happens in a run, as it happens. It is what a UI shows while it waits: the text arriving, and which tool is
 * running in between, which is most of the wait of a run with tools.
 *
 * A type of its own and not [StreamPart] because a model never produces the tool and step events: they are of the
 * run. What the model does produce travels inside [Model], exactly as the adapter read it.
 */
sealed interface RunEvent {
    /** A call to the model started. [number] counts from one. */
    data class StepStarted(val number: Int): RunEvent

    /** Something the model is producing: a delta, an item that finished, an event we do not map. */
    data class Model(val part: StreamPart): RunEvent

    data class ToolStarted(val call: ToolCallPart): RunEvent

    /** [failure] is there when the tool failed; [result] is what the model will read either way. */
    data class ToolFinished(val result: ToolResultPart, val failure: ToolFailure? = null): RunEvent

    /** The call and its tools are over. The step itself is in the result of the run. */
    data class StepFinished(val number: Int): RunEvent
}
