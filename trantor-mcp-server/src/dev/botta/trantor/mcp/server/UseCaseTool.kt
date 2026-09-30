package dev.botta.trantor.mcp.server

import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import dev.botta.trantor.primitives.serialization.JsonSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/**
 * A use case of the application as a tool of an MCP endpoint, which runs as a route that is a use case does.
 *
 * - Its args are the request: the model is given the schema the serializer of the application reads for it, with its
 *   descriptions and its validations, and what it sends is read by that same serializer, as the body of a route is.
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
    override val readOnly: Boolean,
): Tool<Request<*>>(type) {
    /** Not strict: the schema says what the serializer takes, and strict mode would have the provider rewrite it. */
    override fun spec(serializer: JsonSerializer) = super.spec(serializer).copy(strict = false)

    override fun execute(args: Request<*>, context: ToolContext): ToolResult {
        val response = context.run.require<McpCall>().execute(args)
        if (response == null || response == Unit) return ToolResult.text("Done.")

        return context.json(response)
    }

    internal companion object {
        /**
         * The tool of the use case [type], whose schema the [serializer] says, which is asked for now so that a type
         * it cannot tell fails when the endpoint is declared. A `Query` only reads, unless [readOnly] says otherwise.
         */
        fun of(
            type: KType,
            name: String,
            description: String,
            serializer: JsonSerializer,
            readOnly: Boolean?,
        ): UseCaseTool {
            if (serializer !is JsonSchemaSource) throw McpServerError(
                "The tool $name needs the schema of $type, and the serializer of the application, " +
                    "${serializer::class.simpleName}, is not a JsonSchemaSource that can tell it",
            )

            val query = (type.classifier as KClass<*>).isSubclassOf(Query::class)
            val tool = UseCaseTool(name, description, type, readOnly ?: query)

            try {
                tool.spec(serializer)
            } catch (e: JsonSchemaError) {
                throw McpServerError("The tool $name cannot tell the model what $type takes: ${e.message}", e)
            }

            return tool
        }
    }
}
