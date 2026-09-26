package dev.botta.trantor.ai.telemetry

import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.primitives.TrantorBuildInfo
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.api.metrics.Meter

/**
 * The metrics of the models, the tools and the agents, following the semantic conventions for generative AI at the
 * same commit as [GenAITelemetry], with the units and the buckets they give. Durations are in seconds.
 *
 * - `gen_ai.client.operation.duration` for each call to the model, and `time_to_first_chunk` and
 *   `time_per_output_chunk` for the ones received as they happen.
 * - The tokens twice, as the conventions ask: counters of what was spent (`gen_ai.client.inference.usage.*`), for
 *   totals and rates, and histograms of each call (`gen_ai.client.inference.operation.*`), for percentiles. Trantor
 *   cannot tell text tokens from image or audio ones, so the counters say `unknown`, which is what the conventions
 *   ask for then.
 * - `gen_ai.execute_tool.duration` for each tool, and for each agent — a generation, an agent alone or the stretch
 *   of an agent in a workflow — `gen_ai.invoke_agent.duration` and how many calls to the model and tools it made
 *   itself; a workflow, `gen_ai.invoke_workflow.duration`.
 *
 * Without a meter, because the telemetry does not export or failed, every instrument is null and nothing is
 * recorded.
 */
internal class GenAIMetrics(openTelemetry: OpenTelemetry) {
    private val meter: Meter? = safely("get a meter") {
        openTelemetry.meterBuilder(INSTRUMENTATION).setInstrumentationVersion(TrantorBuildInfo.version).build()
    }

    private val operationDuration = seconds("gen_ai.client.operation.duration", "GenAI operation duration.")
    private val timeToFirstChunk = seconds(
        "gen_ai.client.operation.time_to_first_chunk",
        "Time to receive the first chunk, from when the request was issued.",
    )
    private val timePerOutputChunk = seconds(
        "gen_ai.client.operation.time_per_output_chunk",
        "Time from the end of the previous chunk to the end of this one.",
    )
    private val inputTokens = tokens("gen_ai.client.inference.usage.input_tokens", "Input tokens, cached included.")
    private val outputTokens = tokens("gen_ai.client.inference.usage.output_tokens", "Output tokens.")
    private val cacheReadTokens =
        tokens("gen_ai.client.inference.usage.cache_read.input_tokens", "Input tokens served from the cache.")
    private val cacheWriteTokens =
        tokens("gen_ai.client.inference.usage.cache_write.input_tokens", "Input tokens written to the cache.")
    private val reasoningTokens =
        tokens("gen_ai.client.inference.usage.reasoning.output_tokens", "Output tokens used for reasoning.")
    private val operationInputTokens =
        tokensPerCall("gen_ai.client.inference.operation.input_tokens", "Input tokens of each call.")
    private val operationOutputTokens =
        tokensPerCall("gen_ai.client.inference.operation.output_tokens", "Output tokens of each call.")
    private val toolDuration = seconds("gen_ai.execute_tool.duration", "Duration of a tool execution.")
    private val agentDuration =
        seconds("gen_ai.invoke_agent.duration", "Duration of an agent invocation.", AGENT_DURATION_BUCKETS)
    private val agentInferenceCalls = calls(
        "gen_ai.invoke_agent.inference_calls", "{inference_call}", "Calls to the model an agent made itself.",
    )
    private val agentToolCalls =
        calls("gen_ai.invoke_agent.tool_calls", "{tool_call}", "Tool calls an agent made itself.")
    private val workflowDuration =
        seconds("gen_ai.invoke_workflow.duration", "Duration of a workflow invocation.", WORKFLOW_DURATION_BUCKETS)

    /** A call to the model that ended, well or with [error]; [usage] once it answered. */
    fun chat(attributes: Attributes, seconds: Double, usage: Usage?, error: Throwable?) = safely("record a call") {
        operationDuration?.record(seconds, withError(attributes, error))
        usage?.let { tokens(attributes, it) }
    }

    fun firstChunk(attributes: Attributes, seconds: Double) =
        safely("record a chunk") { timeToFirstChunk?.record(seconds, attributes) }

    fun nextChunk(attributes: Attributes, seconds: Double) =
        safely("record a chunk") { timePerOutputChunk?.record(seconds, attributes) }

    fun tool(attributes: Attributes, seconds: Double, error: Throwable?) =
        safely("record a tool") { toolDuration?.record(seconds, withError(attributes, error)) }

    fun agent(attributes: Attributes, seconds: Double, inferenceCalls: Int, toolCalls: Int, error: Throwable?) =
        safely("record an agent") {
            agentDuration?.record(seconds, withError(attributes, error))
            // By its name alone: the conventions give these two no other attribute
            val agent = attributes.get(AGENT_NAME)?.let { Attributes.of(AGENT_NAME, it) } ?: Attributes.empty()
            agentInferenceCalls?.record(inferenceCalls.toLong(), agent)
            agentToolCalls?.record(toolCalls.toLong(), agent)
        }

    fun workflow(attributes: Attributes, seconds: Double, error: Throwable?) =
        safely("record a workflow") { workflowDuration?.record(seconds, withError(attributes, error)) }

    private fun tokens(attributes: Attributes, usage: Usage) {
        val counted = attributes.toBuilder().put(MODALITY, "unknown").build()

        usage.inputTokens?.let {
            inputTokens?.add(it.toLong(), counted)
            operationInputTokens?.record(it.toLong(), attributes)
        }
        usage.outputTokens?.let {
            outputTokens?.add(it.toLong(), counted)
            operationOutputTokens?.record(it.toLong(), attributes)
        }
        usage.cacheReadTokens?.let { cacheReadTokens?.add(it.toLong(), counted) }
        usage.cacheWriteTokens?.let { cacheWriteTokens?.add(it.toLong(), counted) }
        usage.reasoningTokens?.let { reasoningTokens?.add(it.toLong(), counted) }
    }

    private fun withError(attributes: Attributes, error: Throwable?) =
        if (error == null) attributes else attributes.toBuilder().put(ERROR_TYPE, error.javaClass.name).build()

    private fun seconds(name: String, description: String, buckets: List<Double> = DURATION_BUCKETS): DoubleHistogram? =
        safely("create $name") {
            meter?.histogramBuilder(name)?.setUnit("s")?.setDescription(description)
                ?.setExplicitBucketBoundariesAdvice(buckets)?.build()
        }

    private fun tokens(name: String, description: String): LongCounter? =
        safely("create $name") { meter?.counterBuilder(name)?.setUnit("{token}")?.setDescription(description)?.build() }

    private fun tokensPerCall(name: String, description: String): LongHistogram? = safely("create $name") {
        meter?.histogramBuilder(name)?.ofLongs()?.setUnit("{token}")?.setDescription(description)
            ?.setExplicitBucketBoundariesAdvice(TOKEN_BUCKETS)?.build()
    }

    private fun calls(name: String, unit: String, description: String): LongHistogram? = safely("create $name") {
        meter?.histogramBuilder(name)?.ofLongs()?.setUnit(unit)?.setDescription(description)
            ?.setExplicitBucketBoundariesAdvice(CALL_BUCKETS)?.build()
    }

    companion object {
        private const val INSTRUMENTATION = "dev.botta.trantor.ai"

        val AGENT_NAME = stringKey("gen_ai.agent.name")
        private val MODALITY = stringKey("gen_ai.token.modality")
        private val ERROR_TYPE = stringKey("error.type")

        private val DURATION_BUCKETS =
            listOf(0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92)
        private val AGENT_DURATION_BUCKETS =
            listOf(0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 6.4, 12.8, 25.6, 51.2, 102.4, 204.8, 409.6)
        private val WORKFLOW_DURATION_BUCKETS =
            listOf(1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0, 7200.0)
        private val TOKEN_BUCKETS = listOf(
            1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L, 262144L, 1048576L, 4194304L, 16777216L, 67108864L,
        )
        private val CALL_BUCKETS = listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L)
    }
}
