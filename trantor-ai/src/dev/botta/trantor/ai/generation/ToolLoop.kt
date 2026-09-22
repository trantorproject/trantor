package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext

/**
 * Calls the model, runs the tools it asks for and calls it again with their results, until it answers without
 * asking for more.
 *
 * It is the one tool loop of trantor-ai: a generation with tools and an agent both run on it.
 *
 * Each call carries the answer before it whole — reasoning, signatures and parts we do not model — because that is
 * what lets the provider pick up where it left off. Tools the provider ran on its side are not run again: their
 * results already came in the answer.
 */
class ToolLoop(
    private val model: ChatModel,
    private val tools: List<Tool<*>>,
    /** How many calls to the model a generation may take. Past them, [MaxStepsExceededError]. */
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val run: RunContext = RunContext(),
) {
    private val toolsByName = tools.associateBy { it.name }

    fun run(request: ChatRequest, options: CallOptions = CallOptions()): List<Step> {
        val steps = mutableListOf<Step>()
        var messages = request.messages
        val specs = request.tools + tools.map { it.spec() }

        while (true) {
            throwIfCancelled(options)

            val response = model.generate(request.copy(messages = messages, tools = specs), options)
            val calls = response.toolCalls.filterNot { it.providerExecuted }

            if (calls.isEmpty()) return steps + Step(response)

            if (steps.size + 1 >= maxSteps) throw MaxStepsExceededError(maxSteps, steps + Step(response))

            val results = calls.map { execute(it) }
            steps.add(Step(response, results))
            messages = messages + response.asMessage() + Message.Tool(results)
        }
    }

    private fun execute(call: ToolCallPart): ToolResultPart {
        val tool = toolsByName[call.toolName] ?: error("There is no tool called ${call.toolName}")
        val result = tool.call(call.input, ToolContext(call.callId, call.toolName, run))

        return ToolResultPart(call.callId, call.toolName, result.output)
    }

    private fun throwIfCancelled(options: CallOptions) {
        options.cancellation?.throwIfCancelled()

        if (Thread.currentThread().isInterrupted) throw CancelledError("The thread was interrupted")
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 10
    }
}
