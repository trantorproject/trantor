@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.primitives.TrantorBuildInfo
import dev.botta.trantor.web.client.HttpMethods
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * A client of an MCP server over Streamable HTTP, on the 2026-07-28 revision. The fixtures are what a server of the
 * official TypeScript SDK v2 answered (trantor-tester/mcp-server).
 */
class McpClientTest {
    @Nested
    inner class `listing tools` {
        @Test
        fun `lists the tools of the server with their schemas`() {
            httpClient.answer(fixture("tools-list.json"))

            val tools = client.listTools()

            assertThat(tools.map { it.name })
                .containsExactly("get_weather", "count", "divide", "deploy", "clima_en_ñuñoa")
            assertThat(tools.first().title).isEqualTo("Weather")
            assertThat(tools.first().description).isEqualTo("The current weather of a city, in celsius")
            assertThat(tools.first().inputSchema.path("properties.city.description")?.asString())
                .isEqualTo("The city and country")
            assertThat(tools.first().outputSchema).isNull()
            assertThat(tools.single { it.name == "divide" }.outputSchema?.path("properties.result.type")?.asString())
                .isEqualTo("number")
        }

        @Test
        fun `follows the next cursor until the last page`() {
            httpClient.answer(fixture("tools-list-page-1.json"))
            httpClient.answer(fixture("tools-list-page-2.json"))
            httpClient.answer(fixture("tools-list-page-3.json"))

            val tools = client.listTools()

            assertThat(tools.map { it.name }).containsExactly("alpha", "bravo", "charlie", "delta", "echo")
            assertThat(sentBodies().map { it.path("params.cursor")?.asString() }).containsExactly(null, "2", "4")
        }

        @Test
        fun `names no tool in the headers`() {
            httpClient.answer(fixture("tools-list.json"))

            client.listTools()

            assertThat(httpClient.request?.headers).containsEntry("Mcp-Method", "tools/list")
            assertThat(httpClient.request?.headers).doesNotContainKey("Mcp-Name")
        }
    }

    @Nested
    inner class `calling a tool` {
        @Test
        fun `sends the name and the arguments, and reads the content`() {
            httpClient.answer(fixture("call-weather.json"))

            val result = client.callTool("get_weather", Json.obj("city" to "Rosario, Argentina"))

            assertThat(sentBody()["method"]?.asString()).isEqualTo("tools/call")
            assertThat(sentBody().path("params.name")?.asString()).isEqualTo("get_weather")
            assertThat(sentBody().path("params.arguments").toString()).isEqualTo("""{"city":"Rosario, Argentina"}""")
            assertThat(result.content)
                .containsExactly(McpContent.Text("It is 18 degrees and sunny in Rosario, Argentina."))
            assertThat(result.isError).isFalse()
        }

        @Test
        fun `reads the structured content along with the text`() {
            httpClient.answer(fixture("call-divide.json"))

            val result = client.callTool("divide", Json.obj("a" to 10, "b" to 4))

            assertThat(result.content).containsExactly(McpContent.Text("""{"result":2.5}"""))
            assertThat(result.structuredContent.toString()).isEqualTo("""{"result":2.5}""")
        }

        @Test
        fun `reads the answer from an event stream, skipping the notifications before it`() {
            httpClient.answer(fixture("call-count-stream.txt"), contentType = "text/event-stream")

            val result = client.callTool("count", Json.obj("to" to 3))

            assertThat(result.content).containsExactly(McpContent.Text("Counted to 3."))
        }

        @Test
        fun `a tool that failed comes back marked as an error, not as an exception`() {
            httpClient.answer(fixture("call-divide-by-zero.json"))

            val result = client.callTool("divide", Json.obj("a" to 1, "b" to 0))

            assertThat(result.isError).isTrue()
            assertThat(result.content).containsExactly(McpContent.Text("Cannot divide by zero."))
        }

        @Test
        fun `an unknown tool fails with the code the server gave`() {
            httpClient.answer(fixture("call-unknown-tool.json"))

            assertThatThrownBy { client.callTool("launch") }
                .isInstanceOfSatisfying(McpError::class.java) {
                    assertThat(it.code).isEqualTo(-32602)
                    assertThat(it.message).isEqualTo("Tool launch not found")
                }
        }

        @Test
        fun `a server that asks for input fails saying the client cannot give it yet`() {
            httpClient.answer(fixture("call-deploy-input-required.json"))

            assertThatThrownBy { client.callTool("deploy", Json.obj("env" to "production")) }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("deploy")
                .hasMessageContaining("elicitation/create")
        }

        @Test
        fun `an answer that is not 200 fails with its status and what the server said`() {
            httpClient.answer(fixture("call-deploy.json"), status = 400)

            assertThatThrownBy { client.callTool("deploy", Json.obj("env" to "production")) }
                .isInstanceOfSatisfying(McpError::class.java) {
                    assertThat(it.status).isEqualTo(400)
                    assertThat(it.code).isEqualTo(-32021)
                    assertThat(it.message).contains("client capabilities do not declare the required capability")
                }
        }
    }

    @Nested
    inner class `every request` {
        @Test
        fun `is posted to the url of the server`() {
            httpClient.answer(fixture("call-weather.json"))

            client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(httpClient.method).isEqualTo(HttpMethods.Post)
            assertThat(httpClient.request?.url).isEqualTo(URL)
        }

        @Test
        fun `carries the version, the client and its capabilities in _meta`() {
            httpClient.answer(fixture("call-weather.json"))

            client.callTool("get_weather", Json.obj("city" to "Rosario"))

            val meta = sentBody().path("params._meta")!!.asObject()!!

            assertThat(meta["io.modelcontextprotocol/protocolVersion"]?.asString()).isEqualTo("2026-07-28")
            assertThat(meta["io.modelcontextprotocol/clientInfo"].toString())
                .isEqualTo("""{"name":"trantor-ai","version":"${TrantorBuildInfo.version}"}""")
            assertThat(meta["io.modelcontextprotocol/clientCapabilities"].toString()).isEqualTo("{}")
        }

        @Test
        fun `carries the MCP headers and the ones configured`() {
            httpClient.answer(fixture("call-weather.json"))

            client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(httpClient.request?.headers).containsAllEntriesOf(
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/event-stream",
                    "MCP-Protocol-Version" to "2026-07-28",
                    "Mcp-Method" to "tools/call",
                    "Mcp-Name" to "get_weather",
                    "Authorization" to "Bearer secret",
                ),
            )
        }

        @Test
        fun `a name that is not plain ASCII goes in base64 in Mcp-Name`() {
            httpClient.answer(fixture("call-non-ascii-name.json"))

            client.callTool("clima_en_ñuñoa")

            assertThat(httpClient.request?.headers).containsEntry("Mcp-Name", "=?base64?Y2xpbWFfZW5fw7F1w7FvYQ==?=")
        }

        @Test
        fun `has an id of its own`() {
            httpClient.answer(fixture("call-weather.json"))
            httpClient.answer(fixture("call-weather.json"))

            client.callTool("get_weather", Json.obj("city" to "Rosario"))
            client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(sentBodies().map { it["id"].toString() }.distinct()).hasSize(2)
        }
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun sentBodies() = httpClient.requests.map { Json.parse(it.body as String).asObject()!! }

    private fun fixture(name: String) =
        javaClass.getResource("/mcp/$name")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val client = McpClient.http(URL, mapOf("Authorization" to "Bearer secret"), httpClient)

    private companion object {
        const val URL = "http://127.0.0.1:3001/mcp"
    }
}
