package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.errors.AIError

/**
 * The run of an agent used as a tool ended waiting for a person to approve some of its calls, which is not supported:
 * the run that called it cannot pause in its place. It fails that run, since it is a mistake of the application and
 * not something the model can work around. Ask for approval of the call to the agent instead, with a tool guardrail.
 */
class NestedApprovalError(val agent: String, message: String): AIError(message)
