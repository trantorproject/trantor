@file:Suppress("ClassName")

package dev.botta.trantor.mcp.server

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.mcp.McpProtocol
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * An MCP endpoint of the 2026-07-28 revision, without a server in the middle. The requests are the ones the client of
 * trantor-ai sends (McpServerTest has that client talking to a real route); the answers follow the spec and what the
 * server of the TypeScript SDK v2 answered, as recorded in the fixtures of the client.
 */
class McpEndpointTest {
    @Nested
    inner class `server discover` {
        @Test
        fun `says the versions it speaks, that it has tools, who it is and that it cannot be cached`() {
            val answer = post("server/discover")

            assertThat(answer.status).isEqualTo(200)
            assertThat(result(answer)).isEqualTo(
                json(
                    """
                    {
                      "resultType": "complete",
                      "supportedVersions": ["2026-07-28"],
                      "capabilities": {"tools": {}},
                      "instructions": "Tools of the store",
                      "ttlMs": 0,
                      "cacheScope": "private",
                      "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "store", "version": "1.0.0"}}
                    }
                    """,
                ),
            )
        }
    }

    @Nested
    inner class `listing tools` {
        @Test
        fun `gives each tool with its description, its schema and whether it only reads`() {
            val tools = result(post("tools/list"))["tools"]?.asArray()!!.map { it.asObject()!! }

            assertThat(tools.map { it["name"]?.asString() }).containsExactly("echo", "weather", "refund", "explode")
            assertThat(tools.first()["description"]?.asString()).isEqualTo("Says back what it is given")
            assertThat(tools.first().path("inputSchema.properties.text.type")?.asString()).isEqualTo("string")
            assertThat(tools.first().path("inputSchema.required")).isEqualTo(Json.array("text"))
            assertThat(tools.first().path("annotations.readOnlyHint")?.asBoolean()).isTrue()
            assertThat(tools[2]["annotations"]).isNull()
        }

        @Test
        fun `says it cannot be cached, since what each one sees may differ`() {
            val result = result(post("tools/list"))

            assertThat(result["resultType"]?.asString()).isEqualTo("complete")
            assertThat(result["ttlMs"]?.asInt()).isEqualTo(0)
            assertThat(result["cacheScope"]?.asString()).isEqualTo("private")
        }
    }

    @Nested
    inner class `calling a tool` {
        @Test
        fun `answers the text of the tool`() {
            val result = result(call("echo", Json.obj("text" to "hola")))

            assertThat(result).isEqualTo(
                json("""{"resultType": "complete", "content": [{"type": "text", "text": "hola"}]}"""),
            )
        }

        @Test
        fun `answers a json as structured content, and as text for the clients that only read that`() {
            val result = result(call("weather", Json.obj("city" to "Rosario")))

            assertThat(result["structuredContent"]).isEqualTo(json("""{"city": "Rosario", "celsius": 18}"""))
            assertThat(result.path("content")).isEqualTo(
                Json.array(Json.obj("type" to "text", "text" to """{"city":"Rosario","celsius":18}""")),
            )
        }

        @Test
        fun `a tool that fails answers what it said, marked as an error, so the model can fix the call`() {
            val result = result(call("refund", Json.obj("amount" to 500)))

            assertThat(result["isError"]?.asBoolean()).isTrue()
            assertThat(result.path("content")).isEqualTo(
                Json.array(Json.obj("type" to "text", "text" to "Refunds over 100 need a manager")),
            )
        }

        @Test
        fun `arguments that do not fit the tool are an error of the tool too`() {
            val result = result(call("echo", Json.obj("other" to 1)))

            assertThat(result["isError"]?.asBoolean()).isTrue()
            assertThat(result.path("content")?.asArray()?.first()?.asObject()?.get("text")?.asString())
                .contains("text")
        }

        @Test
        fun `what the tool did not expect to throw is an internal error that does not tell what happened`() {
            val answer = call("explode", Json.obj())

            assertThat(answer.status).isEqualTo(200)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.INTERNAL_ERROR)
            assertThat(answer.body).doesNotContain("the database password is hunter2")
        }

        @Test
        fun `a tool it does not have is an error of the request, naming it`() {
            val answer = call("launch", Json.obj())

            assertThat(answer.status).isEqualTo(200)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.INVALID_PARAMS)
            assertThat(error(answer)["message"]?.asString()).contains("launch")
        }

        @Test
        fun `the tool gets the id of the request and its own name`() {
            call("echo", Json.obj("text" to "hola"), id = 42)

            assertThat(echo.context?.callId).isEqualTo("42")
            assertThat(echo.context?.toolName).isEqualTo("echo")
        }
    }

    @Nested
    inner class `the transport` {
        @Test
        fun `answers a ping with an empty result`() {
            assertThat(result(post("ping"))).isEqualTo(json("""{"resultType": "complete"}"""))
        }

        @Test
        fun `answers the id it was asked with, and as json`() {
            val answer = post("ping", id = 7)

            assertThat(body(answer)["id"]?.asInt()).isEqualTo(7)
            assertThat(body(answer)["jsonrpc"]?.asString()).isEqualTo("2.0")
            assertThat(answer.headers).containsEntry("Content-Type", "application/json")
        }

        @Test
        fun `takes a notification with 202 and no body`() {
            val body = Json.obj("jsonrpc" to "2.0", "method" to "notifications/cancelled").toString()

            val answer = endpoint.handle(McpHttpRequest("POST", body, headersFor("notifications/cancelled")))

            assertThat(answer.status).isEqualTo(202)
            assertThat(answer.body).isNull()
        }

        @Test
        fun `a method it does not have is a 404 with method not found`() {
            val answer = post("resources/list")

            assertThat(answer.status).isEqualTo(404)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.METHOD_NOT_FOUND)
        }

        @Test
        fun `a body that is not json is a parse error, and one that is not a request an invalid request`() {
            val notJson = endpoint.handle(McpHttpRequest("POST", "{ not json", headersFor("ping")))
            val notRequest = endpoint.handle(McpHttpRequest("POST", """{"jsonrpc":"2.0","id":1}""", headersFor("ping")))

            assertThat(notJson.status).isEqualTo(400)
            assertThat(error(notJson)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.PARSE_ERROR)
            assertThat(notRequest.status).isEqualTo(400)
            assertThat(error(notRequest)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.INVALID_REQUEST)
        }

        @Test
        fun `only takes POST`() {
            assertThat(endpoint.handle(McpHttpRequest("GET", "")).status).isEqualTo(405)
            assertThat(endpoint.handle(McpHttpRequest("DELETE", "")).status).isEqualTo(405)
            assertThat(endpoint.handle(McpHttpRequest("GET", "")).headers).containsEntry("Allow", "POST")
        }
    }

    @Nested
    inner class `the headers` {
        @Test
        fun `a request without the protocol version header is turned down as a mismatch`() {
            val answer = post("ping", headers = headersFor("ping") - McpProtocol.Headers.PROTOCOL_VERSION)

            assertMismatch(answer)
        }

        @Test
        fun `a version in the header that is not the one of the body is turned down`() {
            val headers = headersFor("ping") + (McpProtocol.Headers.PROTOCOL_VERSION to "2025-11-25")

            assertMismatch(post("ping", headers = headers))
        }

        @Test
        fun `a version it does not speak is turned down listing the ones it does`() {
            val answer = post("ping", version = "2099-01-01")

            assertThat(answer.status).isEqualTo(400)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.UNSUPPORTED_PROTOCOL_VERSION)
            assertThat(error(answer).path("data.supported")).isEqualTo(Json.array("2026-07-28"))
            assertThat(error(answer).path("data.requested")?.asString()).isEqualTo("2099-01-01")
        }

        @Test
        fun `a method in the header that is not the one of the body is turned down, as a missing one`() {
            assertMismatch(post("ping", headers = headersFor("tools/list")))
            assertMismatch(post("ping", headers = headersFor("ping") - McpProtocol.Headers.METHOD))
        }

        @Test
        fun `the name of the tool in the header has to be the one of the body`() {
            assertMismatch(call("echo", Json.obj("text" to "hola"), name = "weather"))
            assertMismatch(call("echo", Json.obj("text" to "hola"), name = null))
        }

        @Test
        fun `a name in base64 is compared once decoded`() {
            val answer = call("echo", Json.obj("text" to "hola"), name = "=?base64?ZWNobw==?=")

            assertThat(result(answer).path("content")?.asArray()?.first()?.asObject()?.get("text")?.asString())
                .isEqualTo("hola")
        }

        @Test
        fun `an answer that turns a request down keeps its id`() {
            val answer = post("ping", id = 9, headers = headersFor("tools/list"))

            assertThat(body(answer)["id"]?.asInt()).isEqualTo(9)
        }

        private fun assertMismatch(answer: McpHttpResponse) {
            assertThat(answer.status).isEqualTo(400)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.HEADER_MISMATCH)
        }
    }

    @Nested
    inner class `a client of before 2026-07-28` {
        @Test
        fun `is greeted in the version it asked for, with no session, since every request stands on its own`() {
            val answer = legacy("initialize", Json.obj("protocolVersion" to "2025-06-18"), header = null)

            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.headers.keys).doesNotContain(McpProtocol.Headers.SESSION)
            assertThat(result(answer)).isEqualTo(
                json(
                    """
                    {
                      "protocolVersion": "2025-06-18",
                      "capabilities": {"tools": {}},
                      "serverInfo": {"name": "store", "version": "1.0.0"},
                      "instructions": "Tools of the store"
                    }
                    """,
                ),
            )
        }

        @Test
        fun `that asks for a version it does not know is greeted in the last one before 2026-07-28`() {
            val answer = legacy("initialize", Json.obj("protocolVersion" to "2024-01-01"), header = null)

            assertThat(result(answer)["protocolVersion"]?.asString()).isEqualTo("2025-11-25")
        }

        @Test
        fun `lists and calls the same tools, with results as that revision has them`() {
            val tools = result(legacy("tools/list"))
            val echo = Json.obj("name" to "echo", "arguments" to Json.obj("text" to "hi"))
            val called = result(legacy("tools/call", echo))

            assertThat(tools["tools"]?.asArray()?.map { it.asObject()?.get("name")?.asString() })
                .containsExactly("echo", "weather", "refund", "explode")
            assertThat(tools.keys).containsExactly("tools")
            assertThat(called).isEqualTo(json("""{"content": [{"type": "text", "text": "hi"}]}"""))
        }

        @Test
        fun `a tool that fails is an error of the tool as well`() {
            val arguments = Json.obj("amount" to 500)

            val result = result(legacy("tools/call", Json.obj("name" to "refund", "arguments" to arguments)))

            assertThat(result["isError"]?.asBoolean()).isTrue()
        }

        @Test
        fun `is answered without the protocol version header, as a client of 2025-03-26 sends it`() {
            assertThat(result(legacy("ping", header = null))).isEqualTo(Json.obj())
        }

        @Test
        fun `that says in the header a version it does not speak is turned down`() {
            val answer = legacy("ping", header = "2024-01-01")

            assertThat(answer.status).isEqualTo(400)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.INVALID_REQUEST)
        }

        @Test
        fun `asking for a method it does not have gets an error and not a 404, which would read as a lost session`() {
            val answer = legacy("resources/list")

            assertThat(answer.status).isEqualTo(200)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.METHOD_NOT_FOUND)
        }

        @Test
        fun `a request that says 2026-07-28 in the header and not in its body is turned down`() {
            val answer = legacy("tools/list", header = McpProtocol.VERSION)

            assertThat(answer.status).isEqualTo(400)
            assertThat(error(answer)["code"]?.asInt()).isEqualTo(McpProtocol.Errors.HEADER_MISMATCH)
        }

        private fun legacy(
            method: String,
            params: JsonObject = JsonObject(),
            header: String? = "2025-11-25",
        ): McpHttpResponse {
            val body = Json.obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)
            val headers = header?.let { mapOf(McpProtocol.Headers.PROTOCOL_VERSION to it) }.orEmpty()

            return endpoint.handle(McpHttpRequest("POST", body.toString(), headers))
        }
    }

    private fun post(
        method: String,
        params: JsonObject = JsonObject(),
        id: Int = 1,
        version: String = McpProtocol.VERSION,
        headers: Map<String, String> = headersFor(method, version = version),
    ): McpHttpResponse {
        val meta = Json.obj(
            McpProtocol.Meta.PROTOCOL_VERSION to version,
            McpProtocol.Meta.CLIENT_INFO to Json.obj("name" to "test", "version" to "1"),
            McpProtocol.Meta.CLIENT_CAPABILITIES to Json.obj(),
        )
        val body = Json.obj(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to JsonObject(params.toList()).with("_meta", meta),
        )

        return endpoint.handle(McpHttpRequest("POST", body.toString(), headers))
    }

    private fun call(tool: String, arguments: JsonObject, id: Int = 1, name: String? = tool) =
        post(
            "tools/call",
            Json.obj("name" to tool, "arguments" to arguments),
            id,
            headers = headersFor("tools/call", name),
        )

    private fun headersFor(method: String, name: String? = null, version: String = McpProtocol.VERSION) = buildMap {
        put(McpProtocol.Headers.PROTOCOL_VERSION, version)
        put(McpProtocol.Headers.METHOD, method)
        name?.let { put(McpProtocol.Headers.NAME, it) }
    }

    private fun body(answer: McpHttpResponse) = Json.parse(answer.body!!).asObject()!!

    private fun result(answer: McpHttpResponse) =
        body(answer)["result"]?.asObject() ?: error("No result: ${answer.body}")

    private fun error(answer: McpHttpResponse) = body(answer)["error"]?.asObject() ?: error("No error: ${answer.body}")

    private fun json(text: String) = Json.parse(text.trimIndent()).asObject()!!

    private val echo = EchoTool()

    private val endpoint = McpEndpoint(
        "store",
        "1.0.0",
        listOf(echo, WeatherTool(), RefundTool(), ExplodingTool()),
        instructions = "Tools of the store",
    )

    class EchoTool: Tool<EchoTool.Args>() {
        override val name = "echo"
        override val description = "Says back what it is given"
        override val readOnly = true
        var context: ToolContext? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            this.context = context
            return ToolResult.text(args.text)
        }

        @Serializable
        data class Args(val text: String)
    }

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "weather"
        override val description = "The weather of a city"
        override val readOnly = true

        override fun execute(args: Args, context: ToolContext) =
            ToolResult.json(Json.obj("city" to args.city, "celsius" to 18))

        @Serializable
        data class Args(val city: String)
    }

    class RefundTool: Tool<RefundTool.Args>() {
        override val name = "refund"
        override val description = "Gives the money of an order back"

        override fun execute(args: Args, context: ToolContext): ToolResult {
            if (args.amount > 100) throw ToolError("Refunds over 100 need a manager")
            return ToolResult.text("Refunded")
        }

        @Serializable
        data class Args(val amount: Int)
    }

    class ExplodingTool: Tool<ExplodingTool.Args>() {
        override val name = "explode"
        override val description = "Fails as nobody expected"

        override fun execute(args: Args, context: ToolContext): ToolResult =
            throw IllegalStateException("the database password is hunter2")

        @Serializable
        class Args
    }
}
