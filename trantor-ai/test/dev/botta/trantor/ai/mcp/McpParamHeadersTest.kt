@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The arguments a server marks with `x-mcp-header`, which a client of 2026-07-28 over HTTP also sends as
 * `Mcp-Param-*` headers. The fixtures under mcp/headers are what the server of the TypeScript SDK v2 answered
 * (trantor-tester/mcp-server): it checks those headers against the body, and turns down a request whose headers do
 * not match.
 */
class McpParamHeadersTest {
    @Nested
    inner class `listing` {
        @Test
        fun `leaves out a tool whose header the spec does not allow, whatever the reason`() {
            httpClient.answer(fixture("tools-list.json"))

            val names = client.listTools().map { it.name }

            assertThat(names).contains("run_query", "get_weather")
            assertThat(names)
                .doesNotContain("header_on_a_number", "header_under_items", "header_twice", "header_with_a_space")
        }
    }

    @Nested
    inner class `calling a tool` {
        @Test
        fun `mirrors the marked arguments into Mcp-Param headers, as the spec writes each type`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))
            client.listTools()

            val arguments = Json.obj("region" to "eu-west-1", "limit" to 10, "dry_run" to true, "query" to "q")

            client.callTool("run_query", arguments)

            assertThat(httpClient.request?.headers).containsAllEntriesOf(
                mapOf("Mcp-Param-Region" to "eu-west-1", "Mcp-Param-Limit" to "10", "Mcp-Param-Dry-Run" to "true"),
            )
        }

        @Test
        fun `a marked argument inside another one goes too`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))
            client.listTools()

            client.callTool("run_query", Json.obj("region" to "eu-west-1", "options" to Json.obj("zone" to "a")))

            assertThat(httpClient.request?.headers).containsEntry("Mcp-Param-Zone", "a")
        }

        @Test
        fun `a marked argument that is null or missing sends no header`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))
            client.listTools()

            client.callTool("run_query", Json.obj("region" to null, "query" to "q"))

            assertThat(httpClient.request?.headers?.keys).noneMatch { it.startsWith("Mcp-Param-") }
        }

        @Test
        fun `a value that is not plain ASCII goes in base64`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))
            client.listTools()

            client.callTool("run_query", Json.obj("region" to "eu-west-1", "options" to Json.obj("zone" to "zona-ñ")))

            assertThat(httpClient.request?.headers).containsEntry("Mcp-Param-Zone", "=?base64?em9uYS3DsQ==?=")
        }

        @Test
        fun `a tool not listed yet is listed once the server says its headers are missing, and called again`() {
            httpClient.answer(fixture("call-missing-header.json"), status = 400)
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))

            client.callTool("run_query", Json.obj("region" to "eu-west-1", "query" to "q"))

            assertThat(sentMethods()).containsExactly("tools/call", "tools/list", "tools/call")
            assertThat(httpClient.request?.headers).containsEntry("Mcp-Param-Region", "eu-west-1")
        }

        @Test
        fun `a server that says the headers do not match is listed again and asked once more`() {
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-missing-header.json"), status = 400)
            httpClient.answer(fixture("tools-list.json"))
            httpClient.answer(fixture("call-run-query.json"))
            client.listTools()

            val result = client.callTool("run_query", Json.obj("region" to "eu-west-1", "query" to "q"))

            assertThat(sentMethods()).containsExactly("tools/list", "tools/call", "tools/list", "tools/call")
            assertThat(result.content.single()).isInstanceOf(McpContent.Text::class.java)
        }
    }

    @Nested
    inner class `a server before 2026-07-28` {
        @Test
        fun `gets no Mcp-Param headers, and keeps all its tools`() {
            httpClient.answer(legacy("stateless-modern-rejected.json"), status = 400)
            httpClient.answer(legacy("stateless-initialize.txt"), contentType = EVENT_STREAM)
            httpClient.answer("", status = 202, contentType = "")
            httpClient.answer(fixture("legacy-tools-list.txt"), contentType = EVENT_STREAM)
            httpClient.answer(fixture("legacy-call-run-query.txt"), contentType = EVENT_STREAM)

            val names = client.listTools().map { it.name }
            client.callTool("run_query", Json.obj("region" to "eu-west-1", "query" to "select 1"))

            assertThat(names).contains("run_query", "header_under_items", "header_twice")
            assertThat(httpClient.request?.headers?.keys).noneMatch { it.startsWith("Mcp-Param-") }
        }
    }

    private fun sentMethods() =
        httpClient.requests.map { Json.parse(it.body as String).asObject()!!["method"]?.asString() }

    private fun fixture(name: String) =
        javaClass.getResource("/mcp/headers/$name")?.readText() ?: error("Missing fixture $name")

    private fun legacy(name: String) =
        javaClass.getResource("/mcp/legacy/$name")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val client = McpClient.http("tester", "http://127.0.0.1:3001/mcp", httpClient = httpClient)

    private companion object {
        const val EVENT_STREAM = "text/event-stream"
    }
}
