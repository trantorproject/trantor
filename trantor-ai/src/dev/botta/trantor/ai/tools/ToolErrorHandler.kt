package dev.botta.trantor.ai.tools

import dev.botta.trantor.ai.models.chat.ToolCallPart

/**
 * Turns an exception of a tool into a message that is safe for the model to read, so that an application can let
 * its own errors through: a `NotFoundError` that reaches the model as "it does not exist".
 *
 * Returns null for an exception it does not know, which leaves it to the next handler and, after the last one, to
 * the generic "Tool execution failed". The exception still reaches the application in the step either way.
 */
fun interface ToolErrorHandler {
    fun handle(error: Throwable, call: ToolCallPart): String?
}
