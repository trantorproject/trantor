package dev.botta.trantor.ai.tools

import dev.botta.trantor.ai.errors.AIError

/**
 * The model called a tool with an input that does not fit its args. It is the model's mistake and the model can fix
 * it, so the tool loop sends it this message and goes on instead of failing the run.
 */
class InvalidToolInputError(val toolName: String, message: String, cause: Throwable? = null): AIError(message, cause)
