package dev.botta.trantor.ai.mcp

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.primitives.logging.getLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An MCP client of a server it starts as a process, and talks to over its standard input and output: a message a
 * line, and the answers told apart by the id of the request, since several can be on the way at once.
 *
 * The process starts with the first request. Which revision it speaks is asked with `server/discover`, as the spec
 * says for stdio: a server of 2026-07-28 answers it, and one of before answers with an error, or not at all, and gets
 * the handshake of before instead. When the process ends on its own, what was on the way fails, and the next request
 * starts it again and asks again.
 */
internal class StdioMcpClient(
    override val name: String,
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDirectory: File? = null,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val probeTimeout: Duration = DEFAULT_PROBE_TIMEOUT,
    private val launcher: McpProcessLauncher = SystemProcesses,
): BaseMcpClient() {
    private val ids = AtomicLong()
    private val lock = Any()

    @Volatile
    private var connection: Connection? = null

    override fun send(request: McpRequest) = connection().send(request)

    /** Ends the process: closes its input, and kills it with what it started if it does not end in time. */
    override fun close() = synchronized(lock) {
        connection?.shutdown()
        connection = null
    }

    private fun connection(): Connection {
        connection?.takeIf { it.isOpen }?.let { return it }

        return synchronized(lock) {
            connection?.takeIf { it.isOpen } ?: open().also { connection = it }
        }
    }

    private fun open(): Connection {
        val process = try {
            launcher.launch(command, env, workingDirectory)
        } catch (e: IOException) {
            val line = command.joinToString(" ")
            throw McpError("Could not start the MCP server $name with $line: ${e.message}", cause = e)
        }

        return Connection(process).apply { start() }
    }

    private inner class Connection(private val process: McpProcess) {
        private val waiting = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
        private val lastErrors = ArrayDeque<String>()
        private lateinit var errorReader: Thread
        private lateinit var revision: Revision

        /** Why it ended, once it did. */
        @Volatile
        private var ended: McpError? = null

        val isOpen get() = ended == null

        fun start() {
            // Both streams are read all the time: a server whose errors nobody reads blocks once the pipe fills up
            errorReader = Thread.ofVirtual().name("mcp-$name-errors").start { readErrors() }
            Thread.ofVirtual().name("mcp-$name").start { readOutput() }
            ProcessesToEnd.add(this)

            try {
                revision = discover()
            } catch (e: Exception) {
                shutdown()
                throw e
            }
        }

        fun send(request: McpRequest): JsonObject {
            val id = ids.incrementAndGet()
            val message = when (val revision = revision) {
                Revision.Current -> McpMessages.request(id, request.method, request.params)
                is Revision.Earlier -> McpMessages.earlierRequest(id, request.method, request.params)
            }
            val answer = exchange(id, message, requestTimeout, cancel = true)
                ?: throw McpError("The MCP server $name did not answer ${request.what} in $requestTimeout")

            return McpMessages.resultOf(answer, request.what)
        }

        fun shutdown() {
            ProcessesToEnd.remove(this)
            runCatching { process.closeInput() }
            if (process.waitFor(GRACE)) return

            process.destroy(force = false)
            if (process.waitFor(GRACE)) return

            process.destroy(force = true)
        }

        /**
         * `server/discover`, which a server of 2026-07-28 answers. A server of before answers with an error it
         * picks, or does not answer at all, so anything but an error of the new revision means the handshake of
         * before; the spec asks not to tell by the code.
         */
        private fun discover(): Revision {
            val id = ids.incrementAndGet()
            // Not cancelled when it gets no answer: a server of before does not know the request
            val answer = exchange(id, McpMessages.request(id, "server/discover"), probeTimeout, cancel = false)
            val error = answer?.get("error")?.asObject()

            if (answer != null && error == null) return Revision.Current
            if (error != null && error["code"]?.asInt() in McpMessages.CURRENT_ERRORS) {
                throw McpMessages.errorOf(error, "server/discover")
            }

            return handshake()
        }

        private fun handshake(): Revision.Earlier {
            val id = ids.incrementAndGet()
            val answer = exchange(id, McpMessages.initialize(id), requestTimeout, cancel = true)
                ?: throw McpError("The MCP server $name did not answer initialize in $requestTimeout")
            val result = McpMessages.resultOf(answer, "initialize")

            write(McpMessages.notification("notifications/initialized"))

            return Revision.Earlier(result["protocolVersion"]?.asString() ?: McpMessages.EARLIER_PROTOCOL_VERSION)
        }

        /** Sends [message] and waits for its answer, or null when it does not come in [timeout]. */
        private fun exchange(id: Long, message: JsonObject, timeout: Duration, cancel: Boolean): JsonObject? {
            val answer = CompletableFuture<JsonObject>()
            waiting[id] = answer
            // It may have ended before the request was waiting, and then nothing would answer it
            ended?.let {
                waiting.remove(id)
                throw it
            }

            write(message)

            return try {
                answer.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                waiting.remove(id)
                if (cancel) runCatching { write(cancelled(id)) }
                null
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        private fun write(message: JsonObject) {
            try {
                process.write(message.toString())
            } catch (e: IOException) {
                throw ended ?: McpError("Could not write to the MCP server $name: ${e.message}", cause = e)
            }
        }

        private fun readOutput() {
            try {
                process.output().forEach(::received)
            } catch (e: IOException) {
                logger.debug("Stopped reading the MCP server $name: ${e.message}")
            }

            // Its output ended, so it did too: what it said on its errors tells why
            errorReader.join(GRACE.inWholeMilliseconds)
            val said = synchronized(lastErrors) { lastErrors.joinToString("\n") }
            val error = McpError("The MCP server $name ended" + if (said.isEmpty()) "" else ". It said:\n$said")

            ended = error
            ProcessesToEnd.remove(this)
            waiting.values.forEach { it.completeExceptionally(error) }
            waiting.clear()
        }

        private fun readErrors() {
            try {
                process.errors().forEach { line ->
                    logger.debug("MCP server $name: $line")
                    synchronized(lastErrors) {
                        lastErrors.addLast(line)
                        if (lastErrors.size > ERRORS_KEPT) lastErrors.removeFirst()
                    }
                }
            } catch (e: IOException) {
                logger.debug("Stopped reading the errors of the MCP server $name: ${e.message}")
            }
        }

        private fun received(line: String) {
            val message = runCatching { Json.parse(line).asObject() }.getOrNull()
                ?: return logger.warn("The MCP server $name wrote a line that is not a message: $line")
            val id = message["id"]
            val method = message["method"]?.asString()

            when {
                method != null && id != null -> answerServer(id, method)
                // A notification, like the progress of a request or a list that changed
                method != null -> Unit
                id != null -> waiting.remove(id.asLong())?.complete(message)
            }
        }

        /**
         * A request of the server: a ping is answered, as the spec asks. Nothing else should come, since the client
         * declares no capabilities, and it is answered as unknown so that the server does not wait for it.
         */
        private fun answerServer(id: JsonValue, method: String) {
            val answer = if (method == "ping") {
                Json.obj("jsonrpc" to "2.0", "id" to id, "result" to Json.obj())
            } else {
                val error = Json.obj("code" to METHOD_NOT_FOUND, "message" to "Method not found: $method")
                Json.obj("jsonrpc" to "2.0", "id" to id, "error" to error)
            }

            runCatching { write(answer) }
        }

        private fun cancelled(id: Long) = McpMessages.notification("notifications/cancelled").with(
            "params",
            Json.obj("requestId" to id, "reason" to "No answer in time"),
        )
    }

    private sealed interface Revision {
        data object Current: Revision

        class Earlier(val version: String): Revision
    }

    /**
     * The processes still running, which end with the application even when nobody closed their client: a server
     * is a process of the system, and it would outlive the application otherwise.
     */
    private object ProcessesToEnd {
        private val running = ConcurrentHashMap.newKeySet<Connection>()

        init {
            Runtime.getRuntime().addShutdownHook(Thread { running.toList().forEach { runCatching { it.shutdown() } } })
        }

        fun add(connection: Connection) = running.add(connection)

        fun remove(connection: Connection) = running.remove(connection)
    }

    companion object {
        val DEFAULT_REQUEST_TIMEOUT = 2.minutes
        private val DEFAULT_PROBE_TIMEOUT = 10.seconds

        /** How long it waits for a process to end, once asked and once told. */
        private val GRACE = 2.seconds
        private const val ERRORS_KEPT = 20
        private const val METHOD_NOT_FOUND = -32601
        private val logger = getLogger<StdioMcpClient>()
    }
}
