package dev.botta.trantor.ai.mcp

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** An MCP server started as a process, as the stdio client talks to it. */
internal interface McpProcess {
    /** Writes one message to its input, as a line. */
    fun write(line: String)

    /** What it writes to its output, a line at a time, until it ends. Read by a single thread. */
    fun output(): Sequence<String>

    /** What it writes to its errors, a line at a time, until it ends. Read by a single thread. */
    fun errors(): Sequence<String>

    val isAlive: Boolean

    /** Closes its input, which is how a server is asked to end. */
    fun closeInput()

    /** Whether it ended within [timeout]. */
    fun waitFor(timeout: Duration): Boolean

    /** Ends it and every process it started, asking first and [force]d after. */
    fun destroy(force: Boolean)
}

internal fun interface McpProcessLauncher {
    fun launch(command: List<String>, env: Map<String, String>, workingDirectory: File?): McpProcess
}

/**
 * Starts servers as processes of the system, as the official SDKs of TypeScript and Python do:
 *
 * - **With only the safe part of the environment of the application**, the same list those SDKs pass, plus what the
 *   application gives. Otherwise a server of a third party, downloaded with npx, would get its keys and passwords;
 *   what a server needs, the application gives it.
 * - **On Windows, a bare command is found on the path with its extension**, since npx is npx.cmd there and a
 *   [ProcessBuilder] only finds executables. So the same command works on every system. Its arguments go through
 *   cmd.exe when it is a .cmd, which is why they come from the application and never from a model.
 */
internal object SystemProcesses: McpProcessLauncher {
    private val WINDOWS = System.getProperty("os.name").startsWith("Windows")

    private val SAFE_ON_WINDOWS = setOf(
        "APPDATA", "COMSPEC", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PATH", "PATHEXT", "PROCESSOR_ARCHITECTURE",
        "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP",
        "USERNAME", "USERPROFILE", "WINDIR",
    )
    private val SAFE_ELSEWHERE = setOf("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER")
    private const val DEFAULT_PATH_EXT = ".COM;.EXE;.BAT;.CMD"

    override fun launch(command: List<String>, env: Map<String, String>, workingDirectory: File?): McpProcess {
        val environment = environment(System.getenv(), env, WINDOWS)
        val program = resolve(command.first(), valueOf(environment, "PATH"), valueOf(environment, "PATHEXT"), WINDOWS)
        val builder = ProcessBuilder(listOf(program) + command.drop(1)).directory(workingDirectory)

        builder.environment().apply {
            clear()
            putAll(environment)
        }

        return SystemProcess(builder.start())
    }

    /** The safe part of [system] and [given], which wins. Names on Windows do not care about case. */
    fun environment(system: Map<String, String>, given: Map<String, String>, windows: Boolean): Map<String, String> {
        val safe = if (windows) SAFE_ON_WINDOWS else SAFE_ELSEWHERE
        return system.filterKeys { name -> safe.any { it.equals(name, ignoreCase = windows) } } + given
    }

    /** [command] as it is, or on Windows, when it is a bare name, the file on [path] with an extension of [pathExt]. */
    fun resolve(command: String, path: String?, pathExt: String?, windows: Boolean): String {
        val bare = '/' !in command && '\\' !in command && '.' !in command
        if (!windows || !bare) return command

        val extensions = (pathExt ?: DEFAULT_PATH_EXT).split(';').filter { it.isNotBlank() }
        for (directory in path.orEmpty().split(';').filter { it.isNotBlank() }) {
            for (extension in extensions.flatMap { listOf(it.lowercase(), it) }.distinct()) {
                File(directory, command + extension).takeIf { it.isFile }?.let { return it.path }
            }
        }

        return command
    }

    private fun valueOf(environment: Map<String, String>, name: String) =
        environment.entries.firstOrNull { it.key.equals(name, ignoreCase = WINDOWS) }?.value
}

internal class SystemProcess(private val process: Process): McpProcess {
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)

    /** The processes it started, as they were seen: once it ends, the system no longer tells which they were. */
    private var tree = emptyList<ProcessHandle>()

    @Synchronized
    override fun write(line: String) {
        input.write(line)
        // Not newLine(), which on Windows would end it with \r\n: the spec delimits messages with \n
        input.write('\n'.code)
        input.flush()
    }

    override fun output() = process.inputStream.bufferedReader(Charsets.UTF_8).lineSequence()

    override fun errors() = process.errorStream.bufferedReader(Charsets.UTF_8).lineSequence()

    override val isAlive get() = process.isAlive

    override fun closeInput() = input.close()

    override fun waitFor(timeout: Duration) = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /**
     * The tree too: on Windows, a server started with npx.cmd is a node under a cmd under another node, and they all
     * outlive the process that started them when only that one ends.
     */
    override fun destroy(force: Boolean) {
        tree = (tree + process.descendants().toList()).distinct()

        for (handle in tree + process.toHandle()) {
            if (force) handle.destroyForcibly() else handle.destroy()
        }
    }
}
