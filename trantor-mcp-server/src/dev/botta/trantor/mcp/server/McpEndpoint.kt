package dev.botta.trantor.mcp.server

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.cqbus.identity.AnonymousIdentity
import dev.botta.cqbus.identity.Identity
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.mcp.McpProtocol
import dev.botta.trantor.ai.tools.InvalidToolInputError
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.core.auth.NotAuthenticatedError
import dev.botta.trantor.core.auth.UnauthorizedAccessError
import dev.botta.trantor.core.validation.ValidationError
import dev.botta.trantor.domain.errors.DomainError
import dev.botta.trantor.primitives.logging.getLogger
import io.opentelemetry.api.OpenTelemetry

/**
 * An MCP server of the 2026-07-28 revision behind one endpoint over HTTP, with [tools] for the models of other
 * applications. Every request stands on its own: there is no session and no handshake, and each one says in its
 * `_meta` which revision it speaks. The answers are JSON; nothing is streamed yet.
 *
 * The clients of the revisions before, which most still are, are served on the same endpoint and without a session,
 * as the TypeScript SDK v2 does with `legacy: 'stateless'`: a request that says nothing of its revision in `_meta`
 * is one of theirs. Their handshake, `initialize`, is answered and remembers nothing, since each of their requests
 * can be answered on its own as well.
 *
 * It knows nothing of the server it runs on: [handle] takes the request and gives the answer, and the route of
 * trantor-web ([mcp]) is a thin layer over it.
 */
class McpEndpoint(
    val name: String,
    val version: String,
    tools: List<Tool<*>>,
    /** What a client should know to use the tools, which it can give to its model. */
    val instructions: String? = null,
    /** Whether a caller the application did not authenticate is answered with 401 on every request. */
    private val requireAuthentication: Boolean = false,
    /** Which tools a caller sees, out of all of them; null when every caller sees every tool. */
    private val visibleTools: ((List<Tool<*>>, Identity) -> List<Tool<*>>)? = null,
    /**
     * What traces and measures each request, following the conventions of OpenTelemetry for MCP: a span that goes
     * on from the trace the client sent in `_meta`, and `mcp.server.operation.duration`. The no-op one costs nothing.
     */
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
    private val tools = tools.toList()
    private val telemetry = McpServerTelemetry(openTelemetry)

    fun handle(request: McpHttpRequest, run: RunContext = RunContext()): McpHttpResponse {
        if (request.method != "POST") return McpHttpResponse(405, headers = mapOf("Allow" to "POST"))

        val message = try {
            Json.parse(request.body).asObject()
        } catch (e: Exception) {
            null
        } ?: return error(null, McpProtocol.Errors.PARSE_ERROR, "The body is not a JSON-RPC message", 400)

        val id = message["id"]
        val method = message["method"]?.asString()
            ?: return error(id, McpProtocol.Errors.INVALID_REQUEST, "The message is not a request", 400)
        val params = message["params"]?.asObject() ?: JsonObject()
        val tool = if (method == "tools/call") params["name"]?.asString() else null
        val known = tools.any { it.name == tool }

        return telemetry.operation(request, method, id, params["_meta"]?.asObject(), tool, known) { operation ->
            answer(request, id, method, params, run, operation)
        }
    }

    private fun answer(
        request: McpHttpRequest,
        id: JsonValue?,
        method: String,
        params: JsonObject,
        run: RunContext,
        operation: McpOperation,
    ): McpHttpResponse {
        // Who asks is asked once, and only when something needs it: it runs a request through the application
        val identity by lazy { run.get<McpCall>()?.identity() ?: AnonymousIdentity() }
        if (requireAuthentication && !identity.isAuthenticated) return unauthenticated(id, operation)
        val visible = lazy {
            val filter = visibleTools ?: return@lazy tools.associateBy { it.name }
            filter(tools, identity).associateBy { it.name }
        }

        // A notification says something and waits for nothing; none of the ones of the revision does anything here
        if (id == null) return McpHttpResponse(202)

        val claimed = params["_meta"]?.asObject()?.get(McpProtocol.Meta.PROTOCOL_VERSION)?.asString()
        val headerVersion = request.header(McpProtocol.Headers.PROTOCOL_VERSION)

        // One that says 2026-07-28 in the header and not in the body is not of before: turnDown says what is wrong
        if (claimed == null && headerVersion != McpProtocol.VERSION) {
            return earlier(id, method, params, headerVersion, run, visible, operation)
        }

        claimed?.let { operation.speaks(it) }
        turnDown(request, id, method, params, operation)?.let { return it }

        return when (method) {
            "server/discover" -> result(id, discover())
            "tools/list" -> result(id, list(visible.value))
            "tools/call" -> call(id, params, run, visible.value, operation)
            "ping" -> result(id, Json.obj())
            else -> error(id, McpProtocol.Errors.METHOD_NOT_FOUND, "Method not found: $method", 404, operation)
        }
    }

    /**
     * The request turned down before it runs: a revision this endpoint does not speak, or headers that do not say
     * what the body says, which a gateway in the middle may have routed by. Null when it can run.
     */
    private fun turnDown(
        request: McpHttpRequest,
        id: JsonValue,
        method: String,
        params: JsonObject,
        operation: McpOperation,
    ): McpHttpResponse? {
        val headerVersion = request.header(McpProtocol.Headers.PROTOCOL_VERSION)
        val bodyVersion = params["_meta"]?.asObject()?.get(McpProtocol.Meta.PROTOCOL_VERSION)?.asString()

        if (headerVersion == null || headerVersion != bodyVersion) {
            return mismatch(id, "The protocol version header says $headerVersion, and the body $bodyVersion", operation)
        }

        if (bodyVersion != McpProtocol.VERSION) {
            val data = Json.obj("supported" to Json.array(McpProtocol.VERSION), "requested" to bodyVersion)
            val message = "Unsupported protocol version: $bodyVersion"
            return error(id, McpProtocol.Errors.UNSUPPORTED_PROTOCOL_VERSION, message, 400, operation, data)
        }

        val headerMethod = request.header(McpProtocol.Headers.METHOD)
        if (headerMethod != method) {
            return mismatch(id, "The method header says $headerMethod, and the body $method", operation)
        }

        if (method == "tools/call") {
            val bodyName = params["name"]?.asString()
            val headerName = request.header(McpProtocol.Headers.NAME)?.let { McpProtocol.decodeHeaderValue(it) }
            if (headerName != bodyName) {
                return mismatch(id, "The name header says $headerName, and the body $bodyName", operation)
            }
        }

        return null
    }

    /** A request of a client of a revision before 2026-07-28, whose results say nothing of their type or cache. */
    private fun earlier(
        id: JsonValue,
        method: String,
        params: JsonObject,
        headerVersion: String?,
        run: RunContext,
        visible: Lazy<Map<String, Tool<*>>>,
        operation: McpOperation,
    ): McpHttpResponse {
        if (method != "initialize" && headerVersion != null && headerVersion !in McpProtocol.EARLIER_VERSIONS) {
            val message = "Unsupported protocol version: $headerVersion"
            return error(id, McpProtocol.Errors.INVALID_REQUEST, message, 400, operation)
        }

        if (method == "initialize") {
            val agreed = agreed(params)
            operation.speaks(agreed)
            return result(id, initialize(agreed), complete = false)
        }
        headerVersion?.let { operation.speaks(it) }

        return when (method) {
            "tools/list" -> result(id, Json.obj("tools" to described(visible.value)), complete = false)
            "tools/call" -> call(id, params, run, visible.value, operation, complete = false)
            "ping" -> result(id, Json.obj(), complete = false)
            // Not a 404, which tells a client of before that its session is gone, and it would open another
            else -> error(id, McpProtocol.Errors.METHOD_NOT_FOUND, "Method not found: $method", 200, operation)
        }
    }

    /** The revision of the handshake of before: the one the client asked for when it is one this endpoint speaks. */
    private fun agreed(params: JsonObject) =
        params["protocolVersion"]?.asString()?.takeIf { it in McpProtocol.EARLIER_VERSIONS }
            ?: McpProtocol.EARLIER_VERSION

    private fun initialize(agreed: String): JsonObject {
        return Json.obj(
            "protocolVersion" to agreed,
            "capabilities" to Json.obj("tools" to Json.obj()),
            "serverInfo" to Json.obj("name" to name, "version" to version),
        ).apply { instructions?.let { this["instructions"] = it } }
    }

    private fun discover() = Json.obj(
        "supportedVersions" to Json.array(McpProtocol.VERSION),
        "capabilities" to Json.obj("tools" to Json.obj()),
    ).apply {
        instructions?.let { this["instructions"] = it }
        notCached(this)
        this["_meta"] = Json.obj(McpProtocol.Meta.SERVER_INFO to Json.obj("name" to name, "version" to version))
    }

    private fun list(visible: Map<String, Tool<*>>) = Json.obj("tools" to described(visible)).apply { notCached(this) }

    private fun described(visible: Map<String, Tool<*>>) = Json.array(visible.values.map { describe(it) })

    private fun describe(tool: Tool<*>): JsonObject {
        val spec = tool.spec()
        val description = Json.obj(
            "name" to tool.name,
            "description" to tool.description,
            "inputSchema" to spec.parameters,
        )
        if (tool.readOnly) description["annotations"] = Json.obj("readOnlyHint" to true)

        return description
    }

    private fun call(
        id: JsonValue,
        params: JsonObject,
        run: RunContext,
        visible: Map<String, Tool<*>>,
        operation: McpOperation,
        complete: Boolean = true,
    ): McpHttpResponse {
        val name = params["name"]?.asString()
        // One the caller does not see does not exist for them
        val tool = visible[name]
            ?: return error(id, McpProtocol.Errors.INVALID_PARAMS, "Unknown tool: $name", 200, operation)
        val arguments = params["arguments"]?.asObject() ?: JsonObject()

        val output = try {
            val context = ToolContext(callId = id.asString() ?: id.toString(), toolName = tool.name, run = run)
            tool.call(arguments, context).output
        } catch (e: InvalidToolInputError) {
            return failed(id, e.message, complete, operation)
        } catch (e: ToolError) {
            return failed(id, e.message, complete, operation)
        } catch (e: NotAuthenticatedError) {
            return unauthenticated(id, operation)
        } catch (e: UnauthorizedAccessError) {
            // A permission of the application denied: the model tells the person, who may ask someone who has it
            return failed(id, e.message, complete, operation)
        } catch (e: DomainError) {
            return failed(id, e.message, complete, operation)
        } catch (e: ValidationError) {
            return failed(id, e.message, complete, operation)
        } catch (e: Exception) {
            // What nobody expected may say what it should not, like the insides of the application: it goes to the log
            // and to the trace, which are the application's
            logger.error("The tool $name of the MCP server ${this.name} failed", e)
            return error(id, McpProtocol.Errors.INTERNAL_ERROR, "Internal error", 200, operation, exception = e)
        }

        return result(id, resultOf(output), complete)
    }

    private fun resultOf(output: ToolOutput) = when (output) {
        is ToolOutput.Text -> Json.obj("content" to Json.array(text(output.value)))
        is ToolOutput.Json -> {
            // Structured content is an object in the spec, so a list or a single value goes under result, as the
            // Python SDK and FastMCP do; and as text too, which the spec asks for the clients that only read that
            val structured = output.value as? JsonObject ?: Json.obj("result" to output.value)
            Json.obj("content" to Json.array(text(structured.toString())), "structuredContent" to structured)
        }
    }

    /** A tool that ran and failed as it may: the model reads why, and the result says it is an error. */
    private fun failed(id: JsonValue, message: String?, complete: Boolean, operation: McpOperation): McpHttpResponse {
        operation.toolFailed()
        val result = Json.obj("content" to Json.array(text(message.orEmpty())), "isError" to true)

        return result(id, result, complete)
    }

    private fun text(value: String) = Json.obj("type" to "text", "text" to value)

    /**
     * What a client may keep of a listing, and for how long: nothing, since what each one sees may differ, and a list
     * that changes has no way yet to say so.
     */
    private fun notCached(result: JsonObject) {
        result["ttlMs"] = 0
        result["cacheScope"] = "private"
    }

    /** A result, [complete] with the type every result of 2026-07-28 says, which the ones of before do not have. */
    private fun result(id: JsonValue, result: JsonObject, complete: Boolean = true): McpHttpResponse {
        val typed = if (complete) Json.obj("resultType" to "complete").apply { putAll(result) } else result

        return answer(200, Json.obj("jsonrpc" to "2.0", "id" to id, "result" to typed))
    }

    /**
     * A use case that needs to know who asks, and nobody said: the answer is 401 with the challenge of a Bearer token,
     * which is what makes a client ask the person for credentials.
     */
    private fun unauthenticated(id: JsonValue?, operation: McpOperation): McpHttpResponse {
        val message = "Authentication required"
        operation.answeredError(McpProtocol.Errors.INVALID_REQUEST, message)
        val body = Json.obj(
            "jsonrpc" to "2.0",
            "id" to (id ?: Json.NULL),
            "error" to Json.obj("code" to McpProtocol.Errors.INVALID_REQUEST, "message" to message),
        )

        val headers = mapOf("Content-Type" to "application/json", "WWW-Authenticate" to "Bearer")

        return McpHttpResponse(401, body.toString(), headers)
    }

    private fun mismatch(id: JsonValue, message: String, operation: McpOperation) =
        error(id, McpProtocol.Errors.HEADER_MISMATCH, message, 400, operation)

    /**
     * A JSON-RPC error, which [operation] tells the trace of, with the [exception] behind it when there is one. Without
     * an operation, the message was not a request, and there is no trace of MCP to tell.
     */
    private fun error(
        id: JsonValue?,
        code: Int,
        message: String,
        status: Int,
        operation: McpOperation? = null,
        data: JsonObject? = null,
        exception: Throwable? = null,
    ): McpHttpResponse {
        operation?.answeredError(code, message, exception)
        val error = Json.obj("code" to code, "message" to message)
        data?.let { error["data"] = it }

        return answer(status, Json.obj("jsonrpc" to "2.0", "id" to (id ?: Json.NULL), "error" to error))
    }

    private fun answer(status: Int, body: JsonObject) =
        McpHttpResponse(status, body.toString(), mapOf("Content-Type" to "application/json"))

    private companion object {
        val logger = getLogger<McpEndpoint>()
    }
}
