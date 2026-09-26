package dev.botta.trantor.ai.telemetry

import dev.botta.trantor.ai.agents.AgentRunResult
import dev.botta.trantor.ai.agents.GuardrailVerdict
import dev.botta.trantor.ai.agents.ToolGuardrailVerdict
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.FinishReasons
import dev.botta.trantor.ai.models.chat.OutputSpec
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.primitives.TrantorBuildInfo
import dev.botta.trantor.primitives.logging.getLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.booleanKey
import io.opentelemetry.api.common.AttributeKey.doubleKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringArrayKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * The spans of the models and the tools, following the semantic conventions for generative AI as they are at commit
 * `e57c543` of open-telemetry/semantic-conventions-genai (2026-09-24). They are all in Development and still change
 * often, so every name they give is written here and nowhere else.
 *
 * - `invoke_agent` for a whole generation: it is an agent without a name, which is how other libraries report
 *   theirs, and it adds up the usage of its calls.
 * - `chat {model}` (`CLIENT`) for each call to the model, current while it runs, so that the http call of the
 *   provider is inside it.
 * - `execute_tool {tool}` for each call the loop runs, current while it runs, so that what the tool does is inside.
 *   A tool that fails marks its span alone: the model reads the failure and the run goes on.
 * - For the agents: `invoke_agent {agent}` for an agent alone, and `invoke_workflow {agent}` for a team, with an
 *   `invoke_agent {agent}` for each stretch in which an agent had the conversation. The calls and tools of an agent
 *   say its name.
 * - `run_guardrail {guardrail}` for each guardrail asked, which the conventions do not have yet (see [guardrail]).
 *
 * A stream has the same spans, with parents named instead of current: between one event and the next it is the code
 * of whoever reads that runs, and nothing of the run may be current there. They are current only around what runs
 * at once, and the ones a stream leaves open when it is closed end then, without failing.
 *
 * The content — instructions, messages, tools, args and results — is recorded only when [settings] ask for it, in
 * the shape [GenAIContent] gives it.
 *
 * Telemetry never fails what it watches. A span that cannot be started or written is logged, and the work goes on
 * without it.
 */
internal class GenAISpans(openTelemetry: OpenTelemetry, settings: AITelemetrySettings = AITelemetrySettings()) {
    private val logger = getLogger()
    private val content = if (settings.captureContent) GenAIContent(settings.maxContentLength) else null
    private val tracer: Tracer? =
        safely("get a tracer") { openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version) }

    fun generation(block: () -> RunResult): RunResult {
        val span = start("invoke_agent", SpanKind.INTERNAL) {
            setAttribute(OPERATION, "invoke_agent")
        }

        return traced(span, block) { result -> usage(result.usage) }
    }

    /** A generation received as it happens, which ends when its stream does, hanging from [parent]. */
    fun openGeneration(parent: Context): OpenSpan {
        val span = start("invoke_agent", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "invoke_agent")
        }

        return OpenSpan(span, parent)
    }

    /** A call to [model], by [agent] when an agent makes it, hanging from [parent] or from the current span. */
    fun chat(
        model: ChatModel,
        request: ChatRequest,
        agent: String?,
        parent: Context?,
        block: () -> ChatResponse,
    ): ChatResponse {
        val span = startChat(model, request, agent, parent, streamed = false)

        return traced(span, block) { response -> response(response, agent) }
    }

    /** A call to [model] whose answer is received as it happens, which ends when its stream does. */
    fun openChat(model: ChatModel, request: ChatRequest, agent: String?, parent: Context?) =
        ChatSpan(startChat(model, request, agent, parent, streamed = true), agent)

    private fun startChat(model: ChatModel, request: ChatRequest, agent: String?, parent: Context?, streamed: Boolean) =
        start("chat ${model.modelId}", SpanKind.CLIENT, parent) {
            setAttribute(OPERATION, "chat")
            agent?.let { setAttribute(AGENT_NAME, it) }
            setAttribute(PROVIDER, model.provider)
            setAttribute(REQUEST_MODEL, model.modelId)
            request.settings.maxOutputTokens?.let { setAttribute(MAX_TOKENS, it.toLong()) }
            request.settings.temperature?.let { setAttribute(TEMPERATURE, it) }
            request.settings.topP?.let { setAttribute(TOP_P, it) }
            request.settings.stopSequences?.let { setAttribute(STOP_SEQUENCES, it) }
            // Only when the request asks for a format, and plain text is not one
            if (request.output is OutputSpec.Json) setAttribute(OUTPUT_TYPE, "json")
            // Only when it is streamed: unset means it was not
            if (streamed) setAttribute(STREAM, true)
            content?.let {
                it.systemInstructions(request)?.let { instructions -> setAttribute(SYSTEM_INSTRUCTIONS, instructions) }
                setAttribute(INPUT_MESSAGES, it.inputMessages(request))
                it.toolDefinitions(request.tools)?.let { definitions -> setAttribute(TOOL_DEFINITIONS, definitions) }
            }
        }

    private fun Span.response(response: ChatResponse, agent: String?) {
        response.info.id?.let { setAttribute(RESPONSE_ID, it) }
        setAttribute(RESPONSE_MODEL, response.info.model)
        setAttribute(FINISH_REASONS, listOf(finishReason(response)))
        usage(response.usage)
        content?.let { setAttribute(OUTPUT_MESSAGES, it.outputMessages(response, agent)) }
    }

    /**
     * The call of a [tool], which is null when the model asked for one that does not exist. Its content is the args
     * the tool ran with, after the hooks, and what the model reads of it.
     */
    fun tool(
        call: ToolCallPart,
        tool: Tool<*>?,
        agent: String?,
        parent: Context?,
        block: () -> ToolLoop.Execution,
    ): ToolLoop.Execution {
        val span = start("execute_tool ${call.toolName}", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "execute_tool")
            agent?.let { setAttribute(AGENT_NAME, it) }
            setAttribute(TOOL_NAME, call.toolName)
            setAttribute(TOOL_CALL_ID, call.callId)
            setAttribute(TOOL_TYPE, "function")
            tool?.let { setAttribute(TOOL_DESCRIPTION, it.description) }
        }

        return traced(span, block) { execution ->
            execution.failure?.let { failed(this, it.error) }
            content?.let {
                setAttribute(TOOL_CALL_ARGUMENTS, it.arguments(execution.input ?: call.input))
                setAttribute(TOOL_CALL_RESULT, it.result(execution.result.output))
            }
        }
    }

    /**
     * A run of agents, current while it runs: `invoke_workflow {agent}` for a team, named after the agent it starts
     * with, and `invoke_agent {agent}` for an agent alone. What the run does hangs from [OpenSpan.context].
     */
    fun agentRun(agent: String, workflow: Boolean, block: (OpenSpan) -> AgentRunResult): AgentRunResult {
        val span = startAgentRun(agent, workflow, null)
        val open = OpenSpan(span, Context.current())

        return traced(span, { block(open) }) { result -> usage(result.usage) }
    }

    /** A run of agents received as it happens, which ends when its stream does, hanging from [parent]. */
    fun openAgentRun(agent: String, workflow: Boolean, parent: Context) =
        OpenSpan(startAgentRun(agent, workflow, parent), parent)

    private fun startAgentRun(agent: String, workflow: Boolean, parent: Context?) = if (workflow) {
        start("invoke_workflow $agent", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "invoke_workflow")
            setAttribute(WORKFLOW_NAME, agent)
        }
    } else {
        start("invoke_agent $agent", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "invoke_agent")
            setAttribute(AGENT_NAME, agent)
        }
    }

    /**
     * The stretch of a workflow in which [agent] has the conversation. It starts and ends between two steps, so
     * whoever opens it ends it.
     */
    fun agent(agent: String, parent: Context?): OpenSpan {
        val span = start("invoke_agent $agent", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "invoke_agent")
            setAttribute(AGENT_NAME, agent)
        }

        return OpenSpan(span, parent ?: Context.current())
    }

    /**
     * A guardrail asked about the [target] of a run, `input` or `output`, of the kind [subtype]: `llm` for the
     * conversation or the answer, `tool_call` for a call, whose id is [targetId]. It is current while it checks. A
     * trip is what a guardrail is for, so its span says so without failing: the run it stopped is what fails.
     *
     * The conventions have no guardrail span yet. This follows the proposal in pull request 427 of
     * open-telemetry/semantic-conventions-genai, not merged as of 2026-09-26, with its base attributes alone.
     */
    fun <T: ToolGuardrailVerdict> guardrail(
        name: String,
        target: String,
        subtype: String,
        targetId: String?,
        parent: Context?,
        check: () -> T,
    ): T {
        val span = start("run_guardrail $name", SpanKind.INTERNAL, parent) {
            setAttribute(OPERATION, "run_guardrail")
            setAttribute(GUARDRAIL_NAME, name)
            setAttribute(GUARDRAIL_TARGET, target)
            setAttribute(GUARDRAIL_SUBTYPE, subtype)
            targetId?.let { setAttribute(GUARDRAIL_TARGET_ID, it) }
        }

        return traced(span, check) { verdict ->
            when (verdict) {
                is GuardrailVerdict.Pass -> verdict("allow", null)
                is GuardrailVerdict.Trip -> verdict("deny", verdict.reason)
                is ToolGuardrailVerdict.Reject -> verdict("deny", verdict.message)
            }
        }
    }

    // What the guardrail decided and what the run did about it: here a deny always blocks
    private fun Span.verdict(type: String, reason: String?) {
        setAttribute(GUARDRAIL_VERDICT, type)
        setAttribute(GUARDRAIL_ACTION, if (type == "allow") "allow" else "block")
        reason?.let { setAttribute(GUARDRAIL_REASON, it) }
    }

    /**
     * A span that does not fit around a block, so whoever opened it ends it, once: what comes after the first end is
     * ignored, so a stream read to its end and then closed does not end it twice. Without a span, because the
     * telemetry does not export or failed, it does nothing, and what goes inside hangs from whatever is current.
     */
    inner class OpenSpan internal constructor(private val span: Span?, parent: Context) {
        private var ended = false

        /** What the spans inside hang from. */
        val context: Context? = span?.takeIf { it.spanContext.isValid }?.let { parent.with(it) }

        fun model(modelId: String) {
            safely("write the span") { span?.setAttribute(REQUEST_MODEL, modelId) }
        }

        /** Ends it well, with what it used when it got that far. */
        fun end(usage: Usage?) = ending {
            usage?.let { safely("write the span") { span?.usage(it) } }
        }

        fun fail(error: Throwable) = ending {
            safely("write the span") { span?.let { failed(it, error) } }
        }

        private fun ending(write: () -> Unit) {
            if (ended) return
            ended = true
            write()
            safely("end the span") { span?.end() }
        }
    }

    /**
     * The span of a call whose answer is received as it happens. It is current only while the model opens the
     * stream, so that the http call is inside it; between one part and the next, whoever reads is current.
     */
    inner class ChatSpan internal constructor(private val span: Span?, private val agent: String?) {
        private val issuedAt = System.nanoTime()
        private var chunked = false
        private var ended = false

        /** Runs [open] with this span current, failing it if [open] fails. */
        fun <T> opening(open: () -> T): T {
            if (span == null || !span.spanContext.isValid) return open()

            try {
                return span.makeCurrent().use { open() }
            } catch (e: Throwable) {
                fail(e)
                throw e
            }
        }

        /** A part arrived. The first one says how long it took, from the moment the call was made. */
        fun chunk() {
            if (chunked) return
            chunked = true
            safely("write the span") { span?.setAttribute(TIME_TO_FIRST_CHUNK, (System.nanoTime() - issuedAt) / 1e9) }
        }

        fun end(response: ChatResponse) = ending { span?.response(response, agent) }

        fun fail(error: Throwable) = ending { span?.let { failed(it, error) } }

        /**
         * The stream was closed before its answer came. Whoever read it chose to stop, so it is not an error, but the
         * reason the model stopped never came, which the conventions say as `error`.
         */
        fun cut() = ending { span?.setAttribute(FINISH_REASONS, listOf("error")) }

        private fun ending(write: () -> Unit) {
            if (ended) return
            ended = true
            safely("write the span") { write() }
            safely("end the span") { span?.end() }
        }
    }

    private fun start(name: String, kind: SpanKind, parent: Context? = null, attributes: Span.() -> Unit): Span? {
        val span = safely("start the span $name") {
            tracer?.spanBuilder(name)?.setSpanKind(kind)?.apply { parent?.let { setParent(it) } }?.startSpan()
        } ?: return null
        // Apart, so that a span whose attributes could not be written still ends
        safely("write the span $name") { span.attributes() }

        return span
    }

    /**
     * Runs [block] with [span] current, and ends it with what it gave back or the exception it threw, which goes on
     * as it was.
     */
    private fun <T> traced(span: Span?, block: () -> T, finished: Span.(T) -> Unit): T {
        // Without a span of its own, nothing hides the one of whoever called
        if (span == null || !span.spanContext.isValid) return block()

        try {
            val result = span.makeCurrent().use { block() }
            safely("write the span") { span.finished(result) }
            return result
        } catch (e: Throwable) {
            safely("write the span") { failed(span, e) }
            throw e
        } finally {
            safely("end the span") { span.end() }
        }
    }

    private fun failed(span: Span, error: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.setAttribute(ERROR_TYPE, error.javaClass.name)
        span.recordException(error)
    }

    // The input includes what came from the cache and the output includes the reasoning, as Usage counts them
    private fun Span.usage(usage: Usage) {
        usage.inputTokens?.let { setAttribute(INPUT_TOKENS, it.toLong()) }
        usage.outputTokens?.let { setAttribute(OUTPUT_TOKENS, it.toLong()) }
        usage.cacheReadTokens?.let { setAttribute(CACHE_READ_TOKENS, it.toLong()) }
        usage.cacheWriteTokens?.let { setAttribute(CACHE_WRITE_TOKENS, it.toLong()) }
        usage.reasoningTokens?.let { setAttribute(REASONING_TOKENS, it.toLong()) }
    }

    // The values the conventions give in the models of their messages; a refusal and the rest are ours
    private fun finishReason(response: ChatResponse) = when (response.finishReason) {
        FinishReasons.Stop -> "stop"
        FinishReasons.Length -> "length"
        FinishReasons.ToolCalls -> "tool_call"
        FinishReasons.ContentFilter -> "content_filter"
        FinishReasons.Refusal -> "refusal"
        FinishReasons.Error -> "error"
        FinishReasons.Other -> response.rawFinishReason ?: "other"
    }

    private fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        logger.warn("Could not $what for the telemetry of the models, going on without it", e)
        null
    }

    private companion object {
        const val INSTRUMENTATION = "dev.botta.trantor.ai"

        val OPERATION = stringKey("gen_ai.operation.name")
        val PROVIDER = stringKey("gen_ai.provider.name")
        val REQUEST_MODEL = stringKey("gen_ai.request.model")
        val AGENT_NAME = stringKey("gen_ai.agent.name")
        val WORKFLOW_NAME = stringKey("gen_ai.workflow.name")
        val MAX_TOKENS = longKey("gen_ai.request.max_tokens")
        val TEMPERATURE = doubleKey("gen_ai.request.temperature")
        val TOP_P = doubleKey("gen_ai.request.top_p")
        val STOP_SEQUENCES = stringArrayKey("gen_ai.request.stop_sequences")
        val OUTPUT_TYPE = stringKey("gen_ai.output.type")
        val STREAM = booleanKey("gen_ai.request.stream")
        val TIME_TO_FIRST_CHUNK = doubleKey("gen_ai.response.time_to_first_chunk")
        val RESPONSE_ID = stringKey("gen_ai.response.id")
        val RESPONSE_MODEL = stringKey("gen_ai.response.model")
        val FINISH_REASONS = stringArrayKey("gen_ai.response.finish_reasons")
        val INPUT_TOKENS = longKey("gen_ai.usage.input_tokens")
        val OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens")
        val CACHE_READ_TOKENS = longKey("gen_ai.usage.cache_read.input_tokens")
        val CACHE_WRITE_TOKENS = longKey("gen_ai.usage.cache_write.input_tokens")
        val REASONING_TOKENS = longKey("gen_ai.usage.reasoning.output_tokens")
        val TOOL_NAME = stringKey("gen_ai.tool.name")
        val TOOL_CALL_ID = stringKey("gen_ai.tool.call.id")
        val TOOL_TYPE = stringKey("gen_ai.tool.type")
        val TOOL_DESCRIPTION = stringKey("gen_ai.tool.description")
        val ERROR_TYPE = stringKey("error.type")
        val SYSTEM_INSTRUCTIONS = stringKey("gen_ai.system_instructions")
        val INPUT_MESSAGES = stringKey("gen_ai.input.messages")
        val OUTPUT_MESSAGES = stringKey("gen_ai.output.messages")
        val TOOL_DEFINITIONS = stringKey("gen_ai.tool.definitions")
        val TOOL_CALL_ARGUMENTS = stringKey("gen_ai.tool.call.arguments")
        val TOOL_CALL_RESULT = stringKey("gen_ai.tool.call.result")
        val GUARDRAIL_NAME = stringKey("gen_ai.guardrail.component.name")
        val GUARDRAIL_TARGET = stringKey("gen_ai.guardrail.target.type")
        val GUARDRAIL_SUBTYPE = stringKey("gen_ai.guardrail.target.subtype")
        val GUARDRAIL_TARGET_ID = stringKey("gen_ai.guardrail.target.id")
        val GUARDRAIL_VERDICT = stringKey("gen_ai.guardrail.verdict.type")
        val GUARDRAIL_ACTION = stringKey("gen_ai.guardrail.action.type")
        val GUARDRAIL_REASON = stringKey("gen_ai.guardrail.verdict.reason")
    }
}
