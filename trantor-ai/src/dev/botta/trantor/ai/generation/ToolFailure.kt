package dev.botta.trantor.ai.generation

/**
 * A call that failed, with the exception behind it. The model got a message about it; this is for the application,
 * which is who can tell a bug from a bad input.
 */
data class ToolFailure(val callId: String, val toolName: String, val error: Throwable)
