package dev.botta.trantor.ai.tools

import dev.botta.trantor.ai.errors.AIError

/**
 * A step would tell the model about more than one tool with the same name: the model could not say which one it
 * calls, and a provider like Anthropic turns the request down. It fails before calling the model. With the tools of
 * several MCP servers, each one goes after its client, so they do not clash with each other.
 */
class DuplicateToolError(
    /** The names that more than one tool has. */
    val names: List<String>,
    message: String,
): AIError(message)
