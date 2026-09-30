package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.Step
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.objectAs
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.ai.serialization.defaultJsonSerializer
import kotlin.reflect.typeOf
import kotlin.reflect.KType

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
    /** The serializer of the run, which reads the object of the output tool. */
    private val serializer: JsonSerializer = defaultJsonSerializer,
) {
    /** The conversation the run keeps, compacted, when it compacted it. See [RunResult.compacted]. */
    val compacted get() = result.compacted

    /** Every step, with the agent that ran it. */
    val steps = result.steps.zip(agents) { step, agent -> AgentStep(agent, step) }

    /** The agent of the last step, the one that answered. */
    val lastAgent get() = steps.last().agent

    val response get() = result.response

    val text get() = result.text

    val finishReason get() = result.finishReason

    /** The calls that wait for a person to approve them, each with the agent that made it. See [RunResult.pending]. */
    val pending get() = result.pending

    /** Whether the run ended waiting for a person to approve some of its calls, which are [pending]. */
    val paused get() = result.paused

    /** What the run added to the conversation, for the application to keep. See [RunResult.newMessages]. */
    val newMessages get() = result.newMessages

    val usage get() = result.usage

    val estimatedCost get() = result.estimatedCost

    val warnings get() = result.warnings

    val toolFailures get() = result.toolFailures

    inline fun <reified T: Any> output(): T = output(typeOf<T>())

    /**
     * The object the last agent answered with: the args of its call to the output tool in [OutputMode.Tool], and
     * the text of the answer otherwise.
     *
     * @throws NoObjectGeneratedError when the answer is not one: the model answered without calling the output tool,
     * or its text is not the object, or the run [paused] before answering.
     */
    fun <T: Any> output(type: KType): T {
        if (paused) throw NoObjectGeneratedError(
            "The run is waiting for approval of ${pending.joinToString { it.call.toolName }}, so it has no answer yet",
            finishReason,
            text = text,
        )

        val output = lastAgent.output

        if (output?.mode != OutputMode.Tool) return response.objectAs(type, serializer)

        val call = outputCall() ?: throw NoObjectGeneratedError(
            "The model answered without calling ${OutputTool.NAME}",
            finishReason,
            text = text,
        )

        @Suppress("UNCHECKED_CAST")
        return output.fromArgs(call.input, serializer) as T
    }

    /**
     * The object the last agent answered with, as JSON, for whoever does not know its type, like the agent that used
     * this one as a tool. Null when it answers text, or when the answer is not the object.
     */
    internal fun outputAsJson(): JsonValue? = when (lastAgent.output?.mode) {
        null -> null
        OutputMode.Tool -> outputCall()?.input
        OutputMode.Native -> runCatching { Json.parse(text) }.getOrNull()
            ?.takeIf { it is JsonObject || it is JsonArray }
    }

    /** The call to the output tool that ended the run, in [OutputMode.Tool]. */
    private fun outputCall(): ToolCallPart? {
        val last = steps.last().step
        val answered = last.toolResults.filter { !it.isError && it.toolName == OutputTool.NAME }.map { it.callId }

        return last.response.toolCalls.firstOrNull { it.callId in answered }
    }
}

/** A step of a run of the agents, and the agent that ran it. */
data class AgentStep(val agent: Agent, val step: Step)
