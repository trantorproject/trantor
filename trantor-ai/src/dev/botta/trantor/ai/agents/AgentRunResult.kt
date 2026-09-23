package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.models.chat.objectAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * What a run of the agents left: what [RunResult] says about any run, plus which agent ran each step and the id of
 * the run.
 */
class AgentRunResult internal constructor(
    /** The run as the tool loop left it. */
    val result: RunResult,
    agents: List<Agent>,
    /** Tells this run apart from the others, and is what its tools see in their context. */
    val runId: String,
) {
    /** Every step, with the agent that ran it. */
    val steps = result.steps.zip(agents) { step, agent -> AgentStep(agent, step) }

    /** The agent of the last step, the one that answered. */
    val lastAgent get() = steps.last().agent

    val response get() = result.response

    val text get() = result.text

    val finishReason get() = result.finishReason

    /** What the run added to the conversation, for the application to keep. See [RunResult.newMessages]. */
    val newMessages get() = result.newMessages

    val usage get() = result.usage

    val estimatedCost get() = result.estimatedCost

    val warnings get() = result.warnings

    val toolFailures get() = result.toolFailures

    inline fun <reified T: Any> output(): T = output(serializer<T>())

    /**
     * The object the last agent answered with: the args of its call to the output tool in [OutputMode.Tool], and
     * the text of the answer otherwise.
     *
     * @throws NoObjectGeneratedError when the answer is not one: the model answered without calling the output tool,
     * or its text is not the object.
     */
    fun <T: Any> output(serializer: KSerializer<T>): T {
        val output = lastAgent.output

        if (output?.mode != OutputMode.Tool) return response.objectAs(serializer)

        val last = steps.last().step
        val answered = last.toolResults.filter { !it.isError && it.toolName == OutputTool.NAME }.map { it.callId }
        val call = last.response.toolCalls.firstOrNull { it.callId in answered } ?: throw NoObjectGeneratedError(
            "The model answered without calling ${OutputTool.NAME}",
            finishReason,
            text = text,
        )

        @Suppress("UNCHECKED_CAST")
        return output.fromArgs(call.input) as T
    }
}

/** A step of a run of the agents, and the agent that ran it. */
data class AgentStep(val agent: Agent, val step: Step)
