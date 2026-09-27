package dev.botta.trantor.ai.agents

/**
 * What a [ToolGuardrail] decides about a call: to let it run, to stop the run, to answer the call to the model as an
 * error without running it, or to have a person approve it first.
 */
sealed interface ToolGuardrailVerdict {
    /**
     * The call does not run, and the model reads [message] as its error, so it can try something else. The run goes
     * on, with a warning that says which guardrail rejected what. It is OpenAI Agents' `reject_content`.
     */
    data class Reject(val message: String): ToolGuardrailVerdict

    /**
     * The call waits for a person to approve it, for [reason], and the run ends paused with it in
     * [AgentRunResult.pending], as when its tool asks for approval. The guardrails after this one are still asked, so
     * one that rejects the call or stops the run wins: approving a call never skips a check. The run that picks it up
     * runs it once approved, without asking the guardrails again.
     */
    data class AskForApproval(val reason: String? = null): ToolGuardrailVerdict
}

/**
 * What a guardrail decides: to let the run go on or to stop it. A [ToolGuardrail] can also reject a single call,
 * which is why this is a kind of [ToolGuardrailVerdict] and not the other way round: the compiler keeps an input or
 * output guardrail from rejecting, which would mean nothing there.
 */
sealed interface GuardrailVerdict: ToolGuardrailVerdict {
    data object Pass: GuardrailVerdict

    /**
     * Stops the run with a [GuardrailTrippedError]. [reason] says why, for the application and its logs; [details]
     * carries whatever else the check found, like the score of a classifier.
     */
    data class Trip(val reason: String, val details: Any? = null): GuardrailVerdict
}
