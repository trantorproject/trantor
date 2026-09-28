@file:Suppress("ClassName")

package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.Cancellation
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.testing.TestTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client of an MCP server it starts as a process, and talks to over its standard input and output. The lines the
 * fake server writes are what trantor-tester/mcp-server (node server.mjs --stdio, of the TypeScript SDK v2) and the
 * reference server (npx @modelcontextprotocol/server-everything stdio) wrote, under mcp/stdio. Only the id of each
 * answer is the one of the request it answers: on stdio the answers are told apart by it.
 */
class StdioMcpClientTest {
    @Nested
    inner class `what the server speaks` {
        @Test
        fun `is asked before anything else`() {
            server.speaksCurrent()

            client.listTools()

            val probe = server.process.written.first()
            assertThat(probe["method"]?.asString()).isEqualTo("server/discover")
            val meta = probe.path("params._meta")!!.asObject()!!
            assertThat(meta["io.modelcontextprotocol/protocolVersion"]?.asString()).isEqualTo("2026-07-28")
        }

        @Test
        fun `a server that answers the probe is talked to in 2026-07-28`() {
            server.speaksCurrent()

            val result = client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(server.process.methods()).containsExactly("server/discover", "tools/call")
            assertThat(server.process.written.last().path("params._meta")).isNotNull()
            assertThat(result.content).containsExactly(McpContent.Text("It is 18 degrees and sunny in Rosario."))
        }

        @Test
        fun `a server that does not know the probe gets the handshake of before`() {
            server.speaksEarlier()

            val result = client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(server.process.methods())
                .containsExactly("server/discover", "initialize", "notifications/initialized", "tools/call")
            assertThat(server.process.written[1].path("params.protocolVersion")?.asString()).isEqualTo("2025-11-25")
            assertThat(server.process.written.last().path("params._meta")).isNull()
            assertThat(result.content).containsExactly(McpContent.Text("Echo: hola"))
        }

        @Test
        fun `and so does one that does not answer the probe in time`() {
            server.on("initialize") { reply(it, "everything-initialize.jsonl") }
            server.on("tools/call") { reply(it, "everything-call-echo.jsonl") }

            val result = client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(server.process.methods())
                .containsExactly("server/discover", "initialize", "notifications/initialized", "tools/call")
            assertThat(result.content).containsExactly(McpContent.Text("Echo: hola"))
        }
    }

    @Nested
    inner class `the messages` {
        @Test
        fun `several requests go at once, and each gets its own answer`() {
            server.speaksCurrent()
            client.listTools()
            // Answered backwards once both came, so that an answer can only reach its request by its id
            val asked = CopyOnWriteArrayList<Pair<JsonObject, String>>()
            val answerBackwards = {
                if (asked.size == 2) thread { asked.reversed().forEach { (request, name) -> reply(request, name) } }
            }
            server.on("tools/list") { asked.add(it to "current-tools-list.jsonl"); answerBackwards() }
            server.on("tools/call") { asked.add(it to "current-call-weather.jsonl"); answerBackwards() }

            var tools = emptyList<McpToolDefinition>()
            val listing = thread { tools = client.listTools() }
            val call = client.callTool("get_weather", Json.obj("city" to "Rosario"))
            listing.join()

            assertThat(call.content).containsExactly(McpContent.Text("It is 18 degrees and sunny in Rosario."))
            assertThat(tools.map { it.name }).contains("get_weather", "divide")
        }

        @Test
        fun `what the server says on its own, like a list that changed, is skipped`() {
            server.speaksEarlier()

            val result = client.callTool("echo", Json.obj("message" to "hola"))

            assertThat(result.content).containsExactly(McpContent.Text("Echo: hola"))
        }

        @Test
        fun `a ping of the server is answered`() {
            server.on("server/discover") { reply(it, "everything-discover.jsonl") }
            server.on("initialize") { reply(it, "earlier-initialize.jsonl") }
            server.on("tools/call") { process.say(fixture("earlier-call-ping-client.jsonl").single()) }
            server.onAnswer { answer ->
                val call = server.process.written.last { it["method"]?.asString() == "tools/call" }
                if (answer["id"]?.asInt() == 0) reply(call, "earlier-ping-answered.jsonl")
            }

            val result = client.callTool("ping_client")

            assertThat(server.process.written.single { it["id"]?.asInt() == 0 && it["method"] == null }.toString())
                .isEqualTo("""{"jsonrpc":"2.0","id":0,"result":{}}""")
            assertThat(result.content).containsExactly(McpContent.Text("The client answered the ping."))
        }

        @Test
        fun `a request the server does not answer in time fails, and the server is told to stop it`() {
            server.on("server/discover") { reply(it, "current-discover.jsonl") }

            assertThatThrownBy { client.callTool("get_weather", Json.obj("city" to "Rosario")) }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("tools/call get_weather")

            val call = server.process.written.single { it["method"]?.asString() == "tools/call" }
            val cancelled = server.process.written.single { it["method"]?.asString() == "notifications/cancelled" }
            assertThat(cancelled.path("params.requestId")).isEqualTo(call["id"])
        }
    }

    @Nested
    inner class `timeouts and cancellation` {
        @Test
        fun `a request waits the shorter of the timeouts of the run and the client`() {
            server.on("server/discover") { reply(it, "current-discover.jsonl") }
            val startedAt = System.nanoTime()

            assertThatThrownBy {
                client.callTool("get_weather", Json.obj("city" to "Rosario"), CallOptions(timeout = 100.milliseconds))
            }.isInstanceOf(McpError::class.java).hasMessageContaining("tools/call get_weather")

            assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(900)
        }

        @Test
        fun `a cancelled run tells the server to stop the request, and fails as cancelled`() {
            val cancellation = Cancellation()
            server.on("server/discover") { reply(it, "current-discover.jsonl") }
            server.on("tools/call") { thread { Thread.sleep(50); cancellation.cancel() } }

            assertThatThrownBy {
                client.callTool("get_weather", Json.obj("city" to "Rosario"), CallOptions(cancellation = cancellation))
            }.isInstanceOf(CancelledError::class.java)

            val call = server.process.written.single { it["method"]?.asString() == "tools/call" }
            val cancelled = server.process.written.single { it["method"]?.asString() == "notifications/cancelled" }
            assertThat(cancelled.path("params.requestId")).isEqualTo(call["id"])
        }
    }

    @Nested
    inner class `traces` {
        @Test
        fun `say the transport is a pipe, and carry the context of the trace in _meta`() {
            val telemetry = TestTelemetry()
            server.speaksCurrent()
            val client = StdioMcpClient(
                "tester",
                listOf("node", "server.mjs", "--stdio"),
                probeTimeout = 200.milliseconds,
                launcher = server,
                openTelemetry = telemetry.openTelemetry,
            )

            client.listTools()

            val span = telemetry.named("tools/list")
            assertThat(span.attributes.get(stringKey("network.transport"))).isEqualTo("pipe")
            assertThat(server.process.written.last().path("params._meta.traceparent")?.asString()).contains(span.spanId)
        }
    }

    @Nested
    inner class `the process` {
        @Test
        fun `starts with the first request, not with the client`() {
            server.speaksCurrent()
            val client = client()

            assertThat(server.processes).isEmpty()

            client.listTools()

            assertThat(server.processes).hasSize(1)
            assertThat(server.command).containsExactly("node", "server.mjs", "--stdio")
        }

        @Test
        fun `closing closes its input and waits for it to end`() {
            server.speaksCurrent()
            client.listTools()

            client.close()

            assertThat(server.process.inputClosed).isTrue()
            assertThat(server.process.destroyed).isEmpty()
        }

        @Test
        fun `one that does not end in time is killed, with the processes it started`() {
            server.speaksCurrent()
            server.endsWhenInputCloses = false
            client.listTools()

            client.close()

            assertThat(server.process.destroyed).containsExactly(false, true)
        }

        @Test
        fun `a request in flight when it ends fails, saying what it wrote to its errors`() {
            server.on("server/discover") { reply(it, "current-discover.jsonl") }
            server.on("tools/call") { process.fail("Error: Cannot find module 'weather'") }

            assertThatThrownBy { client.callTool("get_weather", Json.obj("city" to "Rosario")) }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("tester")
                .hasMessageContaining("Error: Cannot find module 'weather'")
        }

        @Test
        fun `one that ended is started again on the next request, and asked again what it speaks`() {
            server.on("server/discover") { reply(it, "current-discover.jsonl") }
            server.on("tools/call") { process.fail("killed") }
            assertThatThrownBy { client.callTool("get_weather", Json.obj("city" to "Rosario")) }
            server.speaksCurrent()

            val result = client.callTool("get_weather", Json.obj("city" to "Rosario"))

            assertThat(server.processes).hasSize(2)
            assertThat(server.process.methods()).containsExactly("server/discover", "tools/call")
            assertThat(result.content).containsExactly(McpContent.Text("It is 18 degrees and sunny in Rosario."))
        }

        @Test
        fun `one that cannot be started fails saying the command`() {
            server.cannotStart = IOException("CreateProcess error=2, The system cannot find the file specified")

            assertThatThrownBy { client.listTools() }
                .isInstanceOf(McpError::class.java)
                .hasMessageContaining("node server.mjs --stdio")
                .hasMessageContaining("CreateProcess error=2")
        }
    }

    @Nested
    inner class `starting it` {
        @Test
        fun `gets only the safe part of the environment of the application, and what is given`() {
            val system = mapOf("PATH" to "/usr/bin", "HOME" to "/home/nico", "OPENAI_API_KEY" to "sk-secret")

            val env = SystemProcesses.environment(system, mapOf("API_KEY" to "given"), windows = false)

            assertThat(env).isEqualTo(mapOf("PATH" to "/usr/bin", "HOME" to "/home/nico", "API_KEY" to "given"))
        }

        @Test
        fun `on Windows, the safe part whatever the case of its names`() {
            val system = mapOf("Path" to "C:\\Windows", "SystemRoot" to "C:\\Windows", "DATABASE_PASSWORD" to "secret")

            val env = SystemProcesses.environment(system, emptyMap(), windows = true)

            assertThat(env).isEqualTo(mapOf("Path" to "C:\\Windows", "SystemRoot" to "C:\\Windows"))
        }

        @Test
        fun `on Windows, a bare command is found on the path with the extension it has`(@TempDir dir: File) {
            val npx = File(dir, "npx.cmd").apply { writeText("") }

            val command = SystemProcesses.resolve("npx", path = dir.path, pathExt = ".EXE;.CMD", windows = true)

            assertThat(command).isEqualTo(npx.path)
        }

        @Test
        fun `elsewhere, or with a path or an extension, the command stays as it is`(@TempDir dir: File) {
            File(dir, "npx.cmd").writeText("")

            assertThat(SystemProcesses.resolve("npx", dir.path, ".CMD", windows = false)).isEqualTo("npx")
            assertThat(SystemProcesses.resolve("npx.cmd", dir.path, ".CMD", windows = true)).isEqualTo("npx.cmd")
            assertThat(SystemProcesses.resolve("tools/npx", dir.path, ".CMD", windows = true)).isEqualTo("tools/npx")
        }
    }

    private fun client() = StdioMcpClient(
        "tester",
        listOf("node", "server.mjs", "--stdio"),
        requestTimeout = 1.seconds,
        probeTimeout = 200.milliseconds,
        launcher = server,
    )

    private val server = FakeServer()
    private val client = client()

    private fun fixture(name: String) =
        javaClass.getResource("/mcp/stdio/$name")?.readText()?.lines()?.filter { it.isNotBlank() }
            ?: error("Missing fixture $name")

    /** Says the recorded lines, with the id of [request] on the answer. */
    private fun reply(request: JsonObject, name: String) = fixture(name).forEach { line ->
        val message = Json.parse(line).asObject()!!
        if (message.containsKey("result") || message.containsKey("error")) message["id"] = request["id"]!!
        server.process.say(message.toString())
    }

    /** A server that answers by method, each request in the order it came. */
    private inner class FakeServer: McpProcessLauncher {
        val processes = mutableListOf<FakeProcess>()
        val process get() = processes.last()
        var command = emptyList<String>()
        var endsWhenInputCloses = true
        var cannotStart: IOException? = null
        private val handlers = mutableMapOf<String, FakeServer.(JsonObject) -> Unit>()
        private var answerHandler: (JsonObject) -> Unit = {}

        fun on(method: String, handler: FakeServer.(JsonObject) -> Unit) {
            handlers[method] = handler
        }

        fun onAnswer(handler: (JsonObject) -> Unit) {
            answerHandler = handler
        }

        fun speaksCurrent() {
            on("server/discover") { reply(it, "current-discover.jsonl") }
            on("tools/list") { reply(it, "current-tools-list.jsonl") }
            on("tools/call") { reply(it, "current-call-weather.jsonl") }
        }

        fun speaksEarlier() {
            on("server/discover") { reply(it, "everything-discover.jsonl") }
            on("initialize") { reply(it, "everything-initialize.jsonl") }
            on("tools/call") { reply(it, "everything-call-echo.jsonl") }
        }

        override fun launch(command: List<String>, env: Map<String, String>, workingDirectory: File?): McpProcess {
            cannotStart?.let { throw it }
            this.command = command
            return FakeProcess().also { processes.add(it) }
        }

        fun received(message: JsonObject) {
            val method = message["method"]?.asString()
            when {
                method == null -> answerHandler(message)
                message.containsKey("id") -> handlers[method]?.invoke(this, message)
            }
        }

        inner class FakeProcess: McpProcess {
            val written = CopyOnWriteArrayList<JsonObject>()
            val destroyed = CopyOnWriteArrayList<Boolean>()
            var inputClosed = false
            private val output = LinkedBlockingQueue<String>()
            private val errors = LinkedBlockingQueue<String>()
            @Volatile private var alive = true

            fun methods() = written.mapNotNull { it["method"]?.asString() }

            fun say(line: String) = output.put(line)

            /** Writes [error] to its errors and ends, as a server that crashed. */
            fun fail(error: String) {
                errors.put(error)
                end()
            }

            override fun write(line: String) {
                val message = Json.parse(line).asObject()!!
                written.add(message)
                received(message)
            }

            override fun output() = generateSequence { output.take().takeIf { it != END } }

            override fun errors() = generateSequence { errors.take().takeIf { it != END } }

            override val isAlive get() = alive

            override fun closeInput() {
                inputClosed = true
                if (endsWhenInputCloses) end()
            }

            override fun waitFor(timeout: Duration) = !alive

            override fun destroy(force: Boolean) {
                destroyed.add(force)
                if (force) end()
            }

            private fun end() {
                alive = false
                output.put(END)
                errors.put(END)
            }
        }
    }

    private companion object {
        const val END = "\u0000end"
    }
}
