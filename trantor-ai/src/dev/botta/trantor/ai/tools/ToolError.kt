package dev.botta.trantor.ai.tools

import dev.botta.trantor.ai.errors.AIError

/**
 * Thrown by a tool to tell the model something it can act on: that a city does not exist, that a date is in the
 * past. Its message goes to the model as the result of the call, and the run goes on.
 *
 * Any other exception reaches the model only as "Tool execution failed", because its message was written for a
 * developer and the model could end up repeating it to the user.
 */
open class ToolError(message: String, cause: Throwable? = null): AIError(message, cause)
