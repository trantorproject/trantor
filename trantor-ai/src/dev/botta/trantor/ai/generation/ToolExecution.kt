package dev.botta.trantor.ai.generation

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.throwIfCancelled
import dev.botta.trantor.ai.tools.ToolOutput

/** What a call of a step left: the result the model reads, and what the run keeps of it. */
internal class ToolExecution(
    val result: ToolResultPart,
    val failure: ToolFailure? = null,
    /** The agent the tool handed the conversation over to, before the step settles it. */
    val handoff: String? = null,
    /** The run of a model the tool made to answer. */
    val run: RunResult? = null,
    /** The args the tool ran with, after the hooks. Null when it did not get that far. */
    val input: JsonObject? = null,
)

/** A call that did not run, and the model reads why. Nothing failed. */
internal fun refused(call: ToolCallPart, message: String) =
    ToolExecution(ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true))

internal fun failed(call: ToolCallPart, error: Throwable, message: String, input: JsonObject? = null) = ToolExecution(
    ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true),
    ToolFailure(call.callId, call.toolName, error),
    input = input,
)

/** Fails when the run was cancelled, or its thread interrupted, which is how a cancellation reaches a tool. */
internal fun throwIfCancelled(options: CallOptions) {
    options.cancellation?.throwIfCancelled()

    if (Thread.currentThread().isInterrupted) throw CancelledError("The thread was interrupted")
}
