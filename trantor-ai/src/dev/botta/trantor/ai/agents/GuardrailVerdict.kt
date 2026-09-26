package dev.botta.trantor.ai.agents

/**
 * What a [ToolGuardrail] decides about a call: to let it run, to stop the run, or to answer the call to the model as
 * an error without running it.
 */
sealed interface ToolGuardrailVerdict {
    /**
     * The call does not run, and the model reads [message] as its error, so it can try something else. The run goes
     * on, with a warning that says which guardrail rejected what. It is OpenAI Agents' `reject_content`.
     */
    data class Reject(val message: String): ToolGuardrailVerdict
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
