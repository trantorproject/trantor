@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.Cancellation
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.TextPart
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolError
import dev.botta.trantor.ai.tools.ToolOutput
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The tools of an MCP server, as tools of trantor-ai for a generation or an agent. */
class McpToolsTest {
    @Nested
    inner class `names` {
        @Test
        fun `names each tool after its client, so two servers can have the same one`() {
            val github = FakeMcpClient("github", definition("create_issue"))
            val linear = FakeMcpClient("linear", definition("create_issue"))

            assertThat(github.tools().map { it.name }).containsExactly("github_create_issue")
            assertThat(linear.tools().map { it.name }).containsExactly("linear_create_issue")
        }

        @Test
        fun `turns what the providers do not take in a name into underscores`() {
            val client = FakeMcpClient("my.server", definition("admin.tools.list"), definition("clima_en_ñuñoa"))

            assertThat(client.tools().map { it.name })
                .containsExactly("my_server_admin_tools_list", "my_server_clima_en__u_oa")
        }

        @Test
        fun `cuts a name that is too long, keeping it unique`() {
            val first = "a".repeat(120) + "_first"
            val second = "a".repeat(120) + "_second"
            val names = FakeMcpClient("server", definition(first), definition(second)).tools().map { it.name }

            assertThat(names).allSatisfy { assertThat(it).hasSizeLessThanOrEqualTo(128).matches("[a-zA-Z0-9_-]+") }
            assertThat(names.toSet()).hasSize(2)
        }

        @Test
        fun `two tools that end up with the same name fail when listed`() {
            val client = FakeMcpClient("server", definition("get.weather"), definition("get_weather"))

            assertThatThrownBy { client.tools() }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("get.weather")
                .hasMessageContaining("get_weather")
                .hasMessageContaining("server_get_weather")
        }
    }

    @Nested
    inner class `what the model is told` {
        @Test
        fun `the schema of the server as it is, not strictly`() {
            val schema = Json.parse(WEATHER_SCHEMA).asObject()!!
            val client = FakeMcpClient("tester", definition("get_weather", "The weather of a city", schema))

            val spec = client.tools().single().spec()

            assertThat(spec)
                .isEqualTo(FunctionToolSpec("tester_get_weather", "The weather of a city", schema, strict = false))
        }

        @Test
        fun `the title when the tool has no description`() {
            val client = FakeMcpClient("tester", definition("get_weather", title = "Weather"))

            assertThat(client.tools().single().description).isEqualTo("Weather")
        }
    }

    @Nested
    inner class `choosing` {
        @Test
        fun `takes only the tools asked for`() {
            val client = FakeMcpClient("github", definition("search_issues"), definition("delete_repo"))

            val tools = client.tools(only = setOf("search_issues"))

            assertThat(tools.map { it.name }).containsExactly("github_search_issues")
        }

        @Test
        fun `fails naming a tool the server does not have, so a typo does not go unnoticed`() {
            val client = FakeMcpClient("github", definition("search_issues"))

            assertThatThrownBy { client.tools(readOnly = setOf("search_issue")) }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("search_issue")
                .hasMessageContaining("github")
        }

        @Test
        fun `marks as read only and as needing approval the tools asked for`() {
            val client = FakeMcpClient("github", definition("search_issues"), definition("create_issue"))

            val tools = client.tools(readOnly = setOf("search_issues"), needsApproval = setOf("create_issue"))
                .associateBy { it.definition.name }

            assertThat(tools.getValue("search_issues").readOnly).isTrue()
            assertThat(tools.getValue("search_issues").needsApproval(buildJsonObject {}, context)).isFalse()
            assertThat(tools.getValue("create_issue").readOnly).isFalse()
            assertThat(tools.getValue("create_issue").needsApproval(buildJsonObject {}, context)).isTrue()
        }
    }

    @Nested
    inner class `a call` {
        @Test
        fun `goes to the server with the name it knows and the arguments of the model`() {
            val client = FakeMcpClient("tester", definition("get.weather"))

            client.tools().single().call(Json.obj("city" to "Rosario"), context)

            assertThat(client.calls).containsExactly("get.weather" to Json.obj("city" to "Rosario"))
        }

        @Test
        fun `passes the timeout and the cancellation of its run to the client`() {
            val client = FakeMcpClient("tester", definition("get_weather"))
            val options = CallOptions(timeout = 5.seconds, cancellation = Cancellation())

            val context = ToolContext("call_1", "tool", callOptions = options)

            client.tools().single().call(Json.obj("city" to "Rosario"), context)

            assertThat(client.options).isEqualTo(options)
        }

        @Test
        fun `answers the model with the text of the tool`() {
            val client = FakeMcpClient("tester", definition("get_weather"))
            client.answer = McpToolResult(listOf(McpContent.Text("18 degrees"), McpContent.Text("Sunny")))

            val result = client.tools().single().call(Json.obj("city" to "Rosario"), context)

            assertThat(result.output).isEqualTo(ToolOutput.Text("18 degrees\nSunny"))
        }

        @Test
        fun `answers with the structured content when there is no text`() {
            val client = FakeMcpClient("tester", definition("divide"))
            client.answer = McpToolResult(emptyList(), structuredContent = Json.obj("result" to 2.5))

            val result = client.tools().single().call(Json.obj("a" to 10, "b" to 4), context)

            assertThat(result.output).isEqualTo(ToolOutput.Json(Json.obj("result" to 2.5)))
        }

        @Test
        fun `tells the model what it could not pass on, like an image`() {
            val image = Json.obj("type" to "image", "data" to "iVBORw0KGgo=", "mimeType" to "image/png")
            val client = FakeMcpClient("everything", definition("get-tiny-image"))
            client.answer = McpToolResult(listOf(McpContent.Text("Here is an image"), McpContent.Other("image", image)))

            val result = client.tools().single().call(Json.obj(), context)

            assertThat((result.output as ToolOutput.Text).value.lines()).containsExactly(
                "Here is an image",
                "[The tool also answered with image content, which is not shown here]",
            )
        }

        @Test
        fun `a tool that failed reaches the model as an error it can act on`() {
            val client = FakeMcpClient("tester", definition("divide"))
            client.answer = McpToolResult(listOf(McpContent.Text("Cannot divide by zero.")), isError = true)

            assertThatThrownBy { client.tools().single().call(Json.obj("a" to 1, "b" to 0), context) }
                .isInstanceOf(ToolError::class.java)
                .hasMessage("Cannot divide by zero.")
        }

        @Test
        fun `a call the server turned down fails as any tool that throws`() {
            val client = FakeMcpClient("tester", definition("launch"))
            client.failure = McpError("Tool launch not found", code = -32602)

            assertThatThrownBy { client.tools().single().call(Json.obj(), context) }
                .isInstanceOf(McpError::class.java)
                .isNotInstanceOf(ToolError::class.java)
        }

        @Test
        fun `runs in a generation by the name the model was told`() {
            val client = FakeMcpClient("tester", definition("get.weather"))
            client.answer = McpToolResult(listOf(McpContent.Text("18 degrees")))
            val model = FakeChatModel().answers(
                listOf(ToolCallPart("call_1", "tester_get_weather", Json.obj("city" to "Rosario"))),
                listOf(TextPart("It is 18 degrees")),
            )

            val loop = ToolLoop(model, client.tools(), 5, RunContext())

            val steps = loop.run(ChatRequest("Weather in Rosario?")).steps

            assertThat(client.calls.single().first).isEqualTo("get.weather")
            assertThat(steps.first().toolResults.single())
                .isEqualTo(ToolResultPart("call_1", "tester_get_weather", ToolOutput.Text("18 degrees")))
        }
    }

    private fun definition(
        name: String,
        description: String? = null,
        inputSchema: JsonObject = Json.obj("type" to "object"),
        title: String? = null,
    ) = McpToolDefinition(name, title, description, inputSchema)

    private val context = ToolContext("call_1", "tool")

    private class FakeMcpClient(
        override val name: String,
        private vararg val definitions: McpToolDefinition,
    ): McpClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var answer = McpToolResult(listOf(McpContent.Text("done")))
        var failure: McpError? = null
        var options: CallOptions? = null

        override fun listTools(options: CallOptions) = definitions.toList()

        override fun callTool(name: String, arguments: JsonObject, options: CallOptions): McpToolResult {
            calls.add(name to arguments)
            this.options = options
            failure?.let { throw it }
            return answer
        }

        override fun close() {}
    }

    private companion object {
        const val WEATHER_SCHEMA = """{"type":"object",""" +
            """"${'$'}schema":"https://json-schema.org/draft/2020-12/schema",""" +
            """"properties":{"city":{"type":"string","description":"The city and country"}},"required":["city"]}"""
    }
}
