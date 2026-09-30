package dev.botta.trantor.ai.tools

import com.google.gson.JsonParseException
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.lang.Maybe
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import dev.botta.trantor.primitives.serialization.JsonSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.primaryConstructor

/**
 * A tool the application runs when the model asks for it.
 *
 * Its args are a class of the application, which the serializer of the run reads and describes: the model is given
 * the schema the serializer reads by, so the two cannot drift apart, and the args can be of the types of the domain
 * the application registered, like an id, `Money` or a value object. A tool plays the part of a controller: it turns
 * what the model asked for into an operation of the application and its result into something the model can read.
 *
 * ```kotlin
 * class WeatherTool(private val weather: WeatherService): Tool<WeatherTool.Args>() {
 *     override val name = "getWeather"
 *     override val description = "The current weather of a city, in celsius"
 *
 *     override fun execute(args: Args, context: ToolContext) = context.json(weather.of(args.city))
 *
 *     data class Args(@Description("The city and country") val city: String)
 * }
 * ```
 *
 * An optional arg is best nullable: strict mode makes OpenAI send every field, null when it has nothing, while
 * Anthropic leaves it out. A nullable arg reads as null either way, and an arg that cannot be null reads a null as its
 * default. Args that are a `JsonObject` take the input as the model sent it.
 */
abstract class Tool<TArgs: Any> {
    /** The type of the args, which the serializer of the run reads and describes. */
    val argsType: KType

    /** A tool whose args are the type it extends, as `Tool<Args>` says. */
    constructor() {
        argsType = argsTypeOf(this::class)
    }

    /** A tool that does not know the type of its args, like one generic in them, which is given it. */
    constructor(argsType: KType) {
        this.argsType = argsType
    }

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

    @Volatile
    private var described: Described? = null

    /**
     * What the model is told about the tool, with the schema [serializer] reads its args by, worked out the first time
     * for it. A tool whose schema comes from somewhere else, like one of an MCP server, tells it here.
     *
     * It is strict, holding the model to the schema, unless the args have a `Maybe` of what can be null: in strict mode
     * every field is sent, so the model could never leave that one as it is. The log says so, once.
     *
     * @throws JsonSchemaError when the serializer cannot tell the schema of the args, saying where.
     */
    open fun spec(serializer: JsonSerializer): FunctionToolSpec {
        val schema = describedBy(serializer)
        return FunctionToolSpec(name, description, schema.parameters, schema.strict)
    }

    /**
     * Runs the tool with the input the model sent, read into its args by the serializer of the run.
     *
     * @throws InvalidToolInputError when the input does not fit the args, or an init block of the args rejects it,
     * saying why, so the model can fix the call.
     */
    fun call(input: JsonObject, context: ToolContext) = execute(decode(input, context.serializer), context)

    /** [needsApproval] with the input the model sent; false when it does not fit the args, since it will not run. */
    internal fun asksForApproval(input: JsonObject, context: ToolContext) = try {
        needsApproval(decode(input, context.serializer), context)
    } catch (e: InvalidToolInputError) {
        false
    }

    /** The args the model sent, as the tool reads them. */
    @Suppress("UNCHECKED_CAST")
    internal fun decode(input: JsonObject, serializer: JsonSerializer): TArgs {
        if (isJson) return input as TArgs

        try {
            return serializer.deserialize(input.toString(), (argsType.classifier as KClass<*>).java) as TArgs
        } catch (e: JsonParseException) {
            throw InvalidToolInputError(name, "The input of $name does not fit its args: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // What a require in an init block of the args throws, which is how a type validates itself
            throw InvalidToolInputError(name, "The input of $name is not valid: ${e.message}", e)
        }
    }

    private val isJson get() = argsType.classifier == JsonObject::class

    private fun describedBy(serializer: JsonSerializer): Described {
        described?.takeIf { it.serializer === serializer }?.let { return it }

        if (isJson) return Described(serializer, Json.obj("type" to "object"), strict = false).also { described = it }

        val source = serializer as? JsonSchemaSource ?: throw JsonSchemaError(
            "The tool $name needs the schema of $argsType, and ${serializer::class.simpleName} cannot tell it: the " +
                "serializer of the run has to be a JsonSchemaSource, like the GsonSerializer",
        )
        val maybe = maybeOfNullableIn(argsType)
        if (maybe != null) {
            logger.warn(
                "The tool $name goes without strict mode, which would have the model send $maybe every time: its " +
                    "Maybe of what can be null could never be left as it is",
            )
        }

        return Described(serializer, source.schemaOf(argsType), strict = maybe == null).also { described = it }
    }

    private class Described(val serializer: JsonSerializer, val parameters: JsonObject, val strict: Boolean)

    private companion object {
        val logger = getLogger<Tool<*>>()

        fun argsTypeOf(tool: KClass<*>): KType {
            val declared = tool.allSupertypes.first { it.classifier == Tool::class }.arguments.single().type

            return declared?.takeIf { it.classifier is KClass<*> } ?: throw IllegalStateException(
                "${tool.simpleName} does not say the type of its args, since it is generic in them: give it with the " +
                    "constructor that takes it, as in Tool<T>(typeOf<Args>())",
            )
        }

        /**
         * Where [type] has a `Maybe` of what can be null, by the path to it (`changes[].phone`); null when it has
         * none. It goes down the parameters of the classes it is made of, and what lists and maps hold.
         */
        fun maybeOfNullableIn(type: KType, path: String = "", seen: MutableSet<KClass<*>> = mutableSetOf()): String? {
            val klass = type.classifier as? KClass<*> ?: return null

            if (klass == Maybe::class) {
                val held = type.arguments.firstOrNull()?.type ?: return null
                return path.takeIf { held.isMarkedNullable } ?: maybeOfNullableIn(held, path, seen)
            }

            type.arguments.forEach { argument ->
                argument.type?.let { maybeOfNullableIn(it, "$path[]", seen) }?.let { return it }
            }

            if (klass.java.name.startsWith("java.") || klass.java.name.startsWith("kotlin.") || !seen.add(klass)) {
                return null
            }

            return klass.primaryConstructor?.parameters?.firstNotNullOfOrNull { parameter ->
                val name = if (path.isEmpty()) parameter.name.orEmpty() else "$path.${parameter.name}"
                maybeOfNullableIn(parameter.type, name, seen)
            }
        }
    }
}
