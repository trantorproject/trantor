package dev.botta.trantor.mcp.server

import com.google.gson.JsonParseException
import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.Request
import dev.botta.json.parser.JsonParseError
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.InvalidToolInputError
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import dev.botta.trantor.primitives.serialization.JsonSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import dev.botta.json.Json as BottaJson
import dev.botta.json.values.JsonObject as BottaJsonObject

/**
 * A use case of the application as a tool of an MCP endpoint, which runs as a route that is a use case does.
 *
 * - The model is given the schema the serializer of the application reads for the request, with its descriptions
 *   and its validations; the arguments are read by that same serializer, as the body of a route is.
 * - The request runs with the [McpCall] of the call, through the middlewares of the application: the one that reads
 *   the token tells who asks, and the authorization, the validation and the transactions run as they always do.
 * - What the use case answers is written by the serializer and goes as JSON; a use case that answers nothing says it
 *   was done.
 */
class UseCaseTool internal constructor(
    override val name: String,
    override val description: String,
    /** The request the use case runs, whose schema the model is given. */
    val type: KType,
    private val serializer: JsonSerializer,
    private val schema: BottaJsonObject,
    override val readOnly: Boolean,
): Tool<JsonObject>(JsonObject.serializer()) {
    /** Not strict: the schema says what the serializer takes, and strict mode would have the provider rewrite it. */
    override fun spec() = FunctionToolSpec(name, description, schema, strict = false)

    override fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val response = context.run.require<McpCall>().execute(read(args))
        if (response == null || response == Unit) return ToolResult.text("Done.")

        return ToolResult.json(BottaJson.parse(serializer.serialize(response)))
    }

    /** The request out of what the model sent; what the serializer cannot read goes back to the model to fix. */
    private fun read(args: JsonObject): Request<*> {
        val requestClass = (type.classifier as KClass<*>).java

        return try {
            serializer.deserialize(args.toString(), requestClass) as Request<*>
        } catch (e: JsonParseException) {
            throw InvalidToolInputError(name, e.message ?: "The arguments do not fit $type", e)
        } catch (e: JsonParseError) {
            throw InvalidToolInputError(name, e.message ?: "The arguments do not fit $type", e)
        }
    }

    internal companion object {
        /**
         * The tool of the use case [type], whose schema the [serializer] says. A `Query` only reads, unless [readOnly]
         * says otherwise.
         */
        fun of(
            type: KType,
            name: String,
            description: String,
            serializer: JsonSerializer,
            readOnly: Boolean?,
        ): UseCaseTool {
            val source = serializer as? JsonSchemaSource ?: throw McpServerError(
                "The tool $name needs the schema of $type, and the serializer of the application, " +
                    "${serializer::class.simpleName}, is not a JsonSchemaSource that can tell it",
            )
            val schema = try {
                source.schemaOf(type)
            } catch (e: JsonSchemaError) {
                throw McpServerError("The tool $name cannot tell the model what $type takes: ${e.message}", e)
            }
            val query = (type.classifier as KClass<*>).isSubclassOf(Query::class)

            return UseCaseTool(name, description, type, serializer, schema, readOnly ?: query)
        }
    }
}
