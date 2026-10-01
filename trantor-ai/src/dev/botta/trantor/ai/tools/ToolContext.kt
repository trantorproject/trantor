package dev.botta.trantor.ai.tools

import dev.botta.json.Json
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.serialization.defaultJsonSerializer
import dev.botta.trantor.primitives.serialization.JsonSerializer

/**
 * What a tool knows about the call it is answering. It carries only what every tool can count on, whether it runs
 * inside a plain generation or inside an agent.
 *
 * Invoking a use case is not here: a tool is a service like any other and takes what it needs in its constructor.
 *
 * Inside an agent it is an `AgentToolContext`, which also says which agent called the tool.
 */
open class ToolContext(
    /** The id the provider gave the call, which ties the result to it. */
    val callId: String,
    val toolName: String,
    /** What whoever started the generation passed along, like the tenant or the user it acts for. */
    val run: RunContext = RunContext(),
    /**
     * The timeout, the cancellation and the headers of the run, for a tool that calls a model itself: with them, a
     * cancellation of the run stops that call too.
     */
    val callOptions: CallOptions = CallOptions(),
    /** How the run reads and writes JSON: the serializer of the application, with the types it registered. */
    val serializer: JsonSerializer = defaultJsonSerializer,
) {
    /**
     * [value], an object of the application, as the answer of the tool, written as the application writes JSON: with
     * the types it registered, like an id or `Money`.
     */
    fun json(value: Any): ToolResult = ToolResult.json(Json.parse(serializer.serialize(value)))
}
