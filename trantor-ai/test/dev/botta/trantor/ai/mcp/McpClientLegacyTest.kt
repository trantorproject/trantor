@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.primitives.TrantorBuildInfo
import dev.botta.trantor.web.client.HttpMethods
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * A client of the 2026-07-28 revision talking to a server of an earlier one, which expects the handshake of before.
 * The fixtures under mcp/legacy are what the reference server (server-everything, with sessions) and a 2025-era
 * server of the TypeScript SDK v2 without sessions (trantor-tester/mcp-server, /legacy) answered.
 */
class McpClientLegacyTest {
    @Nested
    inner class `a server before 2026-07-28` {
        @Test
        fun `is asked again with the handshake when it does not understand the request`() {
            everythingOpens()
            streamed("everything-call-echo.txt")

            val result = client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(sentMethods())
                .containsExactly("tools/call", "initialize", "notifications/initialized", "tools/call")
            assertThat(result.content).containsExactly(McpContent.Text("Echo: hola"))
        }

        @Test
        fun `the handshake offers 2025-11-25 and no capabilities, and says who asks`() {
            everythingOpens()
            streamed("everything-call-echo.txt")

            client.callTool("echo", Json.obj("message" to "hola"))

            val params = sentBodies()[1]["params"]!!.asObject()!!

            assertThat(params["protocolVersion"]?.asString()).isEqualTo("2025-11-25")
            assertThat(params["capabilities"].toString()).isEqualTo("{}")
            assertThat(params["clientInfo"].toString())
                .isEqualTo("""{"name":"trantor-ai","version":"${TrantorBuildInfo.version}"}""")
        }

        @Test
        fun `later requests carry the session and the agreed version, and not the metadata of the new revision`() {
            everythingOpens()
            streamed("everything-call-echo.txt")

            client.callTool("echo", Json.obj("message" to "hola"))

            for (request in httpClient.requests.drop(2)) {
                assertThat(request.headers).containsEntry("Mcp-Session-Id", SESSION)
                assertThat(request.headers).containsEntry("MCP-Protocol-Version", "2025-11-25")
            }
            assertThat(sentBodies().last()["params"].toString())
                .isEqualTo("""{"name":"echo","arguments":{"message":"hola"}}""")
        }

        @Test
        fun `the handshake happens once for every request after it`() {
            everythingOpens()
            streamed("everything-tools-list.txt", SESSION)
            streamed("everything-call-echo.txt", SESSION)

            val tools = client.listTools()
            client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(tools.map { it.name }).contains("echo", "get-sum")
            assertThat(sentMethods())
                .containsExactly("tools/list", "initialize", "notifications/initialized", "tools/list", "tools/call")
        }

        @Test
        fun `a server without sessions works too`() {
            httpClient.answer(fixture("stateless-modern-rejected.json"), status = 400)
            streamed("stateless-initialize.txt")
            httpClient.answer("", status = 202, contentType = "")
            streamed("stateless-call-weather.txt")

            val result = client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(result.content).containsExactly(McpContent.Text("It is 18 degrees and sunny in Rosario."))
            assertThat(httpClient.request?.headers).doesNotContainKey("Mcp-Session-Id")
            assertThat(httpClient.request?.headers).containsEntry("MCP-Protocol-Version", "2025-11-25")
        }

        @Test
        fun `a session the server lost is opened again once, and the request goes again`() {
            everythingOpens()
            streamed("everything-call-echo.txt", SESSION)
            httpClient.answer(fixture("everything-session-lost.json"), status = 400)
            everythingOpensAgain()
            streamed("everything-call-echo.txt", NEW_SESSION)

            client.callTool("echo", Json.obj("message" to "hola"))
            val result = client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(result.content).containsExactly(McpContent.Text("Echo: hola"))
            assertThat(sentMethods()).containsExactly(
                "tools/call", "initialize", "notifications/initialized", "tools/call",
                "tools/call", "initialize", "notifications/initialized", "tools/call",
            )
            assertThat(httpClient.request?.headers).containsEntry("Mcp-Session-Id", NEW_SESSION)
        }

        @Test
        fun `a session lost right after it was opened again fails`() {
            everythingOpens()
            httpClient.answer(fixture("everything-session-lost.json"), status = 400)
            everythingOpensAgain()
            httpClient.answer(fixture("everything-session-lost.json"), status = 400)

            assertThatThrownBy { client.callTool("echo", Json.obj("message" to "hola")) }
                .isInstanceOfSatisfying(McpError::class.java) {
                    assertThat(it.status).isEqualTo(400)
                    assertThat(it.message).isEqualTo("Bad Request: No valid session ID provided")
                }
        }

        @Test
        fun `is also told a request was cancelled, since there closing the stream does not say it`() {
            val cancellation = Cancellation()
            everythingOpens()
            streamed("everything-tools-list.txt", SESSION)
            client.listTools()
            streamed("everything-call-echo.txt", SESSION)
            httpClient.answer("", status = 202, contentType = "")
            httpClient.whileReading = { cancellation.cancel() }

            assertThatThrownBy {
                client.callTool("echo", Json.obj("message" to "hola"), CallOptions(cancellation = cancellation))
            }.isInstanceOf(CancelledError::class.java)

            val call = sentBodies().last { it["method"]?.asString() == "tools/call" }
            val cancelled = sentBodies().last()
            assertThat(cancelled["method"]?.asString()).isEqualTo("notifications/cancelled")
            assertThat(cancelled.path("params.requestId")).isEqualTo(call["id"])
            assertThat(httpClient.request?.headers).containsEntry("Mcp-Session-Id", SESSION)
        }

        @Test
        fun `is told as well of a request cancelled while it waits for the answer`() {
            val cancellation = Cancellation()
            everythingOpens()
            streamed("everything-tools-list.txt", SESSION)
            client.listTools()
            httpClient.answer("", status = 202, contentType = "")
            httpClient.whileOpening = { cancellation.cancel() }

            assertThatThrownBy {
                client.callTool("echo", Json.obj("message" to "hola"), CallOptions(cancellation = cancellation))
            }.isInstanceOf(CancelledError::class.java)

            val call = sentBodies().last { it["method"]?.asString() == "tools/call" }
            val cancelled = sentBodies().last()
            assertThat(cancelled["method"]?.asString()).isEqualTo("notifications/cancelled")
            assertThat(cancelled.path("params.requestId")).isEqualTo(call["id"])
        }

        @Test
        fun `closing ends the session`() {
            everythingOpens()
            streamed("everything-call-echo.txt", SESSION)
            httpClient.answer("", contentType = "")

            client.callTool("echo", Json.obj("message" to "hola"))
            client.close()

            assertThat(httpClient.methods.last()).isEqualTo(HttpMethods.Delete)
            assertThat(httpClient.request?.url).isEqualTo(URL)
            assertThat(httpClient.request?.headers).containsEntry("Mcp-Session-Id", SESSION)
        }
    }

    @Nested
    inner class `a server of 2026-07-28` {
        @Test
        fun `that does not support the version fails with the ones it supports, without the handshake`() {
            httpClient.answer(fixture("unsupported-version.json"), status = 400)

            assertThatThrownBy { client.listTools() }
                .isInstanceOfSatisfying(McpError::class.java) {
                    assertThat(it.code).isEqualTo(-32022)
                    assertThat(it.message).contains("supports 2026-07-28")
                }
            assertThat(sentMethods()).containsExactly("tools/list")
        }

        @Test
        fun `that lacks a capability fails without the handshake`() {
            httpClient.answer(javaClass.getResource("/mcp/call-deploy.json")!!.readText(), status = 400)

            assertThatThrownBy { client.callTool("deploy", Json.obj("env" to "production")) }
                .isInstanceOf(McpError::class.java)
            assertThat(sentMethods()).containsExactly("tools/call")
        }

        @Test
        fun `that asks for credentials is not taken for one of before, since only a 400 says that`() {
            httpClient.answer("", status = 401, contentType = "")
            httpClient.answer("", status = 403, contentType = "")

            assertThatThrownBy { client.listTools() }
                .isInstanceOfSatisfying(McpError::class.java) { assertThat(it.status).isEqualTo(401) }
            assertThatThrownBy { client.listTools() }
                .isInstanceOfSatisfying(McpError::class.java) { assertThat(it.status).isEqualTo(403) }
            assertThat(sentMethods()).containsExactly("tools/list", "tools/list")
        }

        @Test
        fun `closing sends nothing, since there is no session`() {
            httpClient.answer(javaClass.getResource("/mcp/call-weather.json")!!.readText())

            client.callTool("get_weather", Json.obj("city" to "Rosario"))
            client.close()

            assertThat(httpClient.methods).containsExactly(HttpMethods.Post)
        }
    }

    /** The reference server turns down a request of the new revision, then opens a session with the handshake. */
    private fun everythingOpens() {
        httpClient.answer(fixture("everything-modern-rejected.json"), status = 400)
        streamed("everything-initialize.txt", SESSION)
        httpClient.answer("", status = 202, contentType = "")
    }

    /** The handshake again, once the server lost the session: it gives a new one. */
    private fun everythingOpensAgain() {
        streamed("everything-initialize.txt", NEW_SESSION)
        httpClient.answer("", status = 202, contentType = "")
    }

    /** An answer on an event stream, with the header of the session the server gives, if any. */
    private fun streamed(name: String, session: String? = null) {
        // As the reference server sends it, in lower case
        val headers = session?.let { mapOf("mcp-session-id" to it) } ?: emptyMap()
        httpClient.answer(fixture(name), contentType = EVENT_STREAM, headers = headers)
    }

    private fun sentBodies() = httpClient.requests.mapNotNull { request ->
        (request.body as String?)?.let { Json.parse(it).asObject() }
    }

    private fun sentMethods() = sentBodies().map { it["method"]?.asString() }

    private fun fixture(name: String) =
        javaClass.getResource("/mcp/legacy/$name")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val client = McpClient.http("everything", URL, httpClient = httpClient)

    private companion object {
        const val URL = "http://127.0.0.1:3002/mcp"
        const val EVENT_STREAM = "text/event-stream"
        const val SESSION = "7b353614-9172-4db6-b8be-92d79947f039"
        const val NEW_SESSION = "0c8d2f4e-5a1b-4c3d-9e7f-6a5b4c3d2e1f"
    }
}
