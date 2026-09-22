package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.primitives.logging.getLogger

/**
 * Calls the model, runs the tools it asks for and calls it again with their results, until it answers without
 * asking for more.
 *
 * It is the one tool loop of trantor-ai: a generation with tools and an agent both run on it.
 *
 * Each call carries the answer before it whole — reasoning, signatures and parts we do not model — because that is
 * what lets the provider pick up where it left off. Tools the provider ran on its side are not run again: their
 * results already came in the answer.
 *
 * A failing tool does not fail the run. The model gets a result marked as an error and can try something else:
 *
 * - Input that does not fit the args, or a tool that does not exist, go back with the detail, since the model can
 *   fix them.
 * - A [ToolError] goes back with its message, which the tool wrote for the model.
 * - Any other exception goes back as "Tool execution failed", unless one of the [errorHandlers] has something safe
 *   to say about it, because its message was written for a developer and could reach the user.
 *
 * The exception itself stays in [Step.toolFailures] and in the log, for the application. A tool with
 * [ToolErrorModes.FailRun] fails the run with its exception instead, and a cancellation always ends it.
 */
class ToolLoop(
    private val model: ChatModel,
    private val tools: List<Tool<*>>,
    /** How many calls to the model a generation may take. Past them, [MaxStepsExceededError]. */
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val run: RunContext = RunContext(),
    /** Asked in order for a safe message about an exception of a tool; the first that answers wins. */
    private val errorHandlers: List<ToolErrorHandler> = emptyList(),
) {
    private val logger = getLogger()
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

            val executions = calls.map { execute(it) }
            val results = executions.map { it.result }
            steps.add(Step(response, results, executions.mapNotNull { it.failure }))
            messages = messages + response.asMessage() + Message.Tool(results)
        }
    }

    private fun execute(call: ToolCallPart): Execution {
        val tool = toolsByName[call.toolName] ?: return unknown(call).let { failed(call, it, it.message!!) }

        return try {
            val result = tool.call(call.input, ToolContext(call.callId, call.toolName, run))
            Execution(ToolResultPart(call.callId, call.toolName, result.output))
        } catch (e: CancelledError) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancelledError("The thread was interrupted while ${call.toolName} ran", e)
        } catch (e: InvalidToolInputError) {
            failed(call, e, e.message!!)
        } catch (e: ToolError) {
            failed(call, e, e.message!!)
        } catch (e: Exception) {
            if (tool.onError == ToolErrorModes.FailRun) throw e

            logger.error("Tool ${call.toolName} failed on call ${call.callId}: ${e.message}", e)
            failed(call, e, errorHandlers.firstNotNullOfOrNull { it.handle(e, call) } ?: GENERIC_FAILURE)
        }
    }

    private fun unknown(call: ToolCallPart) = ToolError(
        "There is no tool called ${call.toolName}. The tools are: ${toolsByName.keys.joinToString()}",
    )

    private fun failed(call: ToolCallPart, error: Throwable, message: String) = Execution(
        ToolResultPart(call.callId, call.toolName, ToolOutput.Text(message), isError = true),
        ToolFailure(call.callId, call.toolName, error),
    )

    private class Execution(val result: ToolResultPart, val failure: ToolFailure? = null)

    private fun throwIfCancelled(options: CallOptions) {
        options.cancellation?.throwIfCancelled()

        if (Thread.currentThread().isInterrupted) throw CancelledError("The thread was interrupted")
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val GENERIC_FAILURE = "Tool execution failed"
    }
}
