package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.generation.RunResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import dev.botta.json.Json as BottaJson
import kotlinx.serialization.json.Json as KotlinxJson

/**
 * What a tool answers, which goes back to the model as the result of its call.
 *
 * A class of its own and not a bare [ToolOutput] so that a tool can later say something for the application apart
 * from what the model reads, without changing the signature of every tool.
 */
data class ToolResult(
    val output: ToolOutput,
    /** The agent of the team the conversation goes to once the step is over, if the tool hands it over. */
    val handoff: String? = null,
    /** The run of a model the tool made to answer, like the one of an agent that runs as a tool. */
    val run: RunResult? = null,
) {
    /**
     * Hands the conversation over to [agent] once the step is over, as a tool that assigns a conversation to sales
     * does. The model still reads [output], and the other calls of the step still run.
     */
    fun handoffTo(agent: String) = copy(handoff = agent)

    /**
     * Says the tool ran a model to answer, and leaves that run in the step: its usage and its cost count as the run's,
     * where a tool that asks a model on its own would otherwise spend without anyone seeing it.
     */
    fun withRun(run: RunResult) = copy(run = run)

    companion object {
        fun text(value: String) = ToolResult(ToolOutput.Text(value))

        fun json(value: JsonValue) = ToolResult(ToolOutput.Json(value))

        /** A @Serializable object, as its JSON. */
        fun <T> json(value: T, serializer: KSerializer<T>) =
            json(BottaJson.parse(KotlinxJson.encodeToString(serializer, value)))

        inline fun <reified T> json(value: T) = json(value, serializer<T>())
    }
}
