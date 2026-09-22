package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.schemas.JsonSchemas
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A tool the application runs when the model asks for it.
 *
 * The args are a @Serializable type, and the schema the model sees comes from the same descriptor that decodes
 * what it sends back, so the two cannot drift apart. A tool plays the part of a controller: it turns what the model
 * asked for into an operation of the application and its result into something the model can read.
 *
 * ```kotlin
 * class WeatherTool(private val weather: WeatherService): Tool<WeatherTool.Args>(Args.serializer()) {
 *     override val name = "getWeather"
 *     override val description = "The current weather of a city, in celsius"
 *
 *     override fun execute(args: Args, context: ToolContext) = ToolResult.json(weather.of(args.city))
 *
 *     @Serializable
 *     data class Args(@SerialDescription("The city and country") val city: String)
 * }
 * ```
 *
 * An optional arg is best nullable: strict mode makes OpenAI send every field, null when it has nothing, while
 * Anthropic leaves it out. A nullable arg reads as null either way.
 */
abstract class Tool<TArgs: Any>(val argsSerializer: KSerializer<TArgs>) {
    abstract val name: String

    /** What the tool is for, which is all the model knows to decide when to call it. */
    abstract val description: String

    /** A tool that only reads can run at the same time as others of the same turn. */
    open val readOnly: Boolean = false

    abstract fun execute(args: TArgs, context: ToolContext): ToolResult

    /** What the model is told about the tool. */
    fun spec() = FunctionToolSpec(name, description, JsonSchemas.of(argsSerializer.descriptor))

    /**
     * Runs the tool with the input the model sent, decoded into its args.
     *
     * @throws InvalidToolInputError when the input does not fit the args, saying which field, so the model can fix
     * the call.
     */
    fun call(input: JsonObject, context: ToolContext) = execute(decode(input), context)

    private fun decode(input: JsonObject): TArgs {
        try {
            return json.decodeFromString(argsSerializer, input.toString())
        } catch (e: SerializationException) {
            throw InvalidToolInputError(name, "The input of $name does not fit its args: ${e.message}", e)
        }
    }

    private companion object {
        // A field the args do not have is no reason to lose the call, and an arg the model left out reads as null
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
