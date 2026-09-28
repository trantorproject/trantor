package dev.botta.trantor.ai.mcp

/**
 * The MCP servers of the application, from the `ai.mcp` section, by the name the application calls each one:
 *
 * ```json
 * "ai": { "mcp": { "servers": {
 *   "github": {
 *     "url": "https://api.githubcopilot.com/mcp/",
 *     "headers": { "Authorization": "Bearer ${GITHUB_TOKEN}" }
 *   },
 *   "files": {
 *     "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"],
 *     "env": { "API_KEY": "${FILES_KEY}" }
 *   }
 * } } }
 * ```
 *
 * A secret goes as a reference to the environment, which the configuration resolves: it never goes in the file.
 */
data class McpSettings(var servers: MutableMap<String, McpServerSettings> = mutableMapOf())

/** One server: a [url] to talk to over HTTP, or a [command] to start and talk to over stdio. */
data class McpServerSettings(
    var url: String? = null,
    /** Sent with every request over HTTP, like the credentials of an API key. */
    var headers: MutableMap<String, String> = mutableMapOf(),
    /** The program and its arguments, like `["npx", "-y", "some-server"]`. */
    var command: MutableList<String>? = null,
    /** Added to the safe part of the environment of the application that the process gets. */
    var env: MutableMap<String, String> = mutableMapOf(),
    var workingDirectory: String? = null,
    /** How long a request waits for its answer, when the run does not say a shorter time. */
    var requestTimeoutSeconds: Long? = null,
)
