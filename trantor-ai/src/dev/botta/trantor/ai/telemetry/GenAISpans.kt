package dev.botta.trantor.ai.telemetry

import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.ToolFailure
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
import io.opentelemetry.api.common.AttributeKey.doubleKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringArrayKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

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
 *
 * The content — instructions, messages, args and results — is not recorded.
 *
 * Telemetry never fails what it watches. A span that cannot be started or written is logged, and the work goes on
 * without it.
 */
internal class GenAISpans(openTelemetry: OpenTelemetry) {
    private val logger = getLogger()
    private val tracer: Tracer? =
        safely("get a tracer") { openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version) }

    fun generation(block: () -> RunResult): RunResult {
        val span = start("invoke_agent", SpanKind.INTERNAL) {
            setAttribute(OPERATION, "invoke_agent")
        }

        return traced(span, block) { result -> usage(result.usage) }
    }

    fun chat(model: ChatModel, request: ChatRequest, block: () -> ChatResponse): ChatResponse {
        val span = start("chat ${model.modelId}", SpanKind.CLIENT) {
            setAttribute(OPERATION, "chat")
            setAttribute(PROVIDER, model.provider)
            setAttribute(REQUEST_MODEL, model.modelId)
            request.settings.maxOutputTokens?.let { setAttribute(MAX_TOKENS, it.toLong()) }
            request.settings.temperature?.let { setAttribute(TEMPERATURE, it) }
            request.settings.topP?.let { setAttribute(TOP_P, it) }
            request.settings.stopSequences?.let { setAttribute(STOP_SEQUENCES, it) }
            // Only when the request asks for a format, and plain text is not one
            if (request.output is OutputSpec.Json) setAttribute(OUTPUT_TYPE, "json")
        }

        return traced(span, block) { response ->
            response.info.id?.let { setAttribute(RESPONSE_ID, it) }
            setAttribute(RESPONSE_MODEL, response.info.model)
            setAttribute(FINISH_REASONS, listOf(finishReason(response)))
            usage(response.usage)
        }
    }

    /** The call of a [tool], which is null when the model asked for one that does not exist. */
    fun <T> tool(call: ToolCallPart, tool: Tool<*>?, block: () -> T, failureOf: (T) -> ToolFailure?): T {
        val span = start("execute_tool ${call.toolName}", SpanKind.INTERNAL) {
            setAttribute(OPERATION, "execute_tool")
            setAttribute(TOOL_NAME, call.toolName)
            setAttribute(TOOL_CALL_ID, call.callId)
            setAttribute(TOOL_TYPE, "function")
            tool?.let { setAttribute(TOOL_DESCRIPTION, it.description) }
        }

        return traced(span, block) { result -> failureOf(result)?.let { failed(this, it.error) } }
    }

    private fun start(name: String, kind: SpanKind, attributes: Span.() -> Unit): Span? {
        val span = safely("start the span $name") { tracer?.spanBuilder(name)?.setSpanKind(kind)?.startSpan() }
            ?: return null
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

    // The conventions give no list of values; these are the ones of the OpenAI chat api, which its examples use
    private fun finishReason(response: ChatResponse) = when (response.finishReason) {
        FinishReasons.Stop -> "stop"
        FinishReasons.Length -> "length"
        FinishReasons.ToolCalls -> "tool_calls"
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
        val MAX_TOKENS = longKey("gen_ai.request.max_tokens")
        val TEMPERATURE = doubleKey("gen_ai.request.temperature")
        val TOP_P = doubleKey("gen_ai.request.top_p")
        val STOP_SEQUENCES = stringArrayKey("gen_ai.request.stop_sequences")
        val OUTPUT_TYPE = stringKey("gen_ai.output.type")
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
    }
}
