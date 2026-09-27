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

    /**
     * A call of the step waits for a person to approve it, and does not run. It comes after the tools of the step that
     * did run and before its [StepFinished], one for each call that waits, and the run ends paused with that step.
     */
    data class ApprovalRequested(val pending: PendingCall): RunEvent

    /**
     * A call the run picked up waiting for approval was answered as not approved, without running: rejected, or left
     * without a decision. It comes before the first step, next to the [ToolStarted] and [ToolFinished] of the approved
     * ones. [result] is what the model will read.
     */
    data class ToolNotApproved(val result: ToolResultPart): RunEvent

    /** The call and its tools are over. The step itself is in the result of the run. */
    data class StepFinished(val number: Int): RunEvent

    /**
     * A tool of the step that just finished handed the conversation over: the next step goes out as [to]. It comes
     * right after that step's [StepFinished], and only in a run of agents, since a generation has nobody to hand the
     * conversation to. [from] is the agent the step went out as.
     */
    data class Handoff(val from: String?, val to: String): RunEvent

    /**
     * A guardrail of the agents stopped the run: the stream ends with this event, and reading on throws the
     * `GuardrailTrippedError`, which has everything else. It comes so that a UI can say the answer was blocked instead
     * of showing a generic error. [guardrail] is its name.
     */
    data class GuardrailTripped(val guardrail: String, val reason: String): RunEvent
}
