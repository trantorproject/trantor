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

    /** A tool that only reads runs at the same time as the other calls of its step, when they all only read. */
    open val readOnly: Boolean = false

    /** What a failure of [execute] does to the run. Input the model got wrong always goes back to it. */
    open val onError: ToolErrorModes = ToolErrorModes.SendToModel

    /**
     * Whether this call waits for a person to approve it before it runs. When one does, the other calls of its step
     * run and the run ends paused, with the call in
     * [RunResult.pending][dev.botta.trantor.ai.generation.RunResult.pending].
     *
     * It is asked with the args as the model sent them, before any hook changes them, which are the args the person
     * sees; so a tool can ask only past an amount, or only for some accounts. Args that do not fit the tool are not
     * asked about: the call goes back to the model as an error, as it would anyway. An exception fails the run.
     */
    open fun needsApproval(args: TArgs, context: ToolContext): Boolean = false

    abstract fun execute(args: TArgs, context: ToolContext): ToolResult

    /**
     * What the model is told about the tool: its schema comes from the args. A tool whose schema comes from
     * somewhere else, like one of an MCP server, tells it here.
     */
    open fun spec() = FunctionToolSpec(name, description, JsonSchemas.of(argsSerializer.descriptor))

    /**
     * Runs the tool with the input the model sent, decoded into its args.
     *
     * @throws InvalidToolInputError when the input does not fit the args, or an init block of the args rejects it,
     * saying why, so the model can fix the call.
     */
    fun call(input: JsonObject, context: ToolContext) = execute(decode(input), context)

    /** [needsApproval] with the input the model sent; false when it does not fit the args, since it will not run. */
    internal fun asksForApproval(input: JsonObject, context: ToolContext) = try {
        needsApproval(decode(input), context)
    } catch (e: InvalidToolInputError) {
        false
    }

    /** The args the model sent, as the tool reads them. */
    internal fun decode(input: JsonObject): TArgs {
        try {
            return json.decodeFromString(argsSerializer, input.toString())
        } catch (e: SerializationException) {
            throw InvalidToolInputError(name, "The input of $name does not fit its args: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // What a require in an init block of the args throws, which is how a type validates itself
            throw InvalidToolInputError(name, "The input of $name is not valid: ${e.message}", e)
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
