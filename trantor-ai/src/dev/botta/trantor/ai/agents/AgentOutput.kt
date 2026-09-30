package dev.botta.trantor.ai.agents

import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSerializer
import kotlin.reflect.KType
import dev.botta.trantor.ai.serialization.schemaFor

/** The object an agent answers with, and how it asks the model for it. */
class AgentOutput<T: Any>(
    /** The type of the object, which the serializer of the run describes to the model and reads back. */
    val type: KType,
    val mode: OutputMode,
) {
    @Volatile
    private var described: Pair<JsonSerializer, JsonObject>? = null

    /** What the model is told the object looks like, by [serializer], worked out the first time for it. */
    internal fun schema(serializer: JsonSerializer): JsonObject {
        described?.takeIf { it.first === serializer }?.let { return it.second }

        return serializer.schemaFor(type, "The output of an agent").also { described = serializer to it }
    }

    private val outputTool = OutputTool<T>(type)

    /** The tool the model answers with, in [OutputMode.Tool]. */
    internal val tool = outputTool.takeIf { mode == OutputMode.Tool }

    /** The object in the args of a call to [tool], read as the tool read them when the call ended the run. */
    internal fun fromArgs(args: JsonObject, serializer: JsonSerializer) = outputTool.decode(args, serializer)
}

/** How an agent asks the model for its object. */
enum class OutputMode {
    /**
     * As the format of the answer, on every step. The model calls its tools with the format set and answers the
     * object once it is done with them. It takes a model that does structured output.
     */
    Native,

    /**
     * As one more tool, whose args are the object: the run ends when the model calls it. It works with any model
     * that takes tools.
     */
    Tool,
}
