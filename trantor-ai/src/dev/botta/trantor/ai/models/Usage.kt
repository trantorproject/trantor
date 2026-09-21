package dev.botta.trantor.ai.models

import dev.botta.json.values.JsonObject

/**
 * Tokens reported by the provider.
 *
 * [inputTokens] and [outputTokens] are **totals**, and the other fields are parts of them, never addends: the input
 * is [uncachedInputTokens] plus [cacheReadTokens] plus [cacheWriteTokens], and [reasoningTokens] is part of the
 * output, no matter how each provider reports them. Adapters normalize to this contract, so that input + output is
 * always the real total and nothing is counted twice.
 *
 * Each part is named for what happened to those tokens, because each is priced apart: read from the cache, written
 * into it, or neither. "Cached" alone would not say which of the first two it means.
 *
 * Null means unknown, which is not the same as zero: some providers don't report usage while streaming.
 */
data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
    // Usage as the provider sent it, for what we don't model yet (audio, images)
    val raw: JsonObject? = null,
) {
    val totalTokens get() = add(inputTokens, outputTokens)

    /** The input that neither came from the cache nor went into it, which is what is billed at the plain price. */
    val uncachedInputTokens get() = inputTokens?.let { it - (cacheReadTokens ?: 0) - (cacheWriteTokens ?: 0) }

    /** Adds up usages of several calls. Keeps no raw, since it belongs to a single call. */
    operator fun plus(other: Usage) = Usage(
        inputTokens = add(inputTokens, other.inputTokens),
        outputTokens = add(outputTokens, other.outputTokens),
        cacheReadTokens = add(cacheReadTokens, other.cacheReadTokens),
        cacheWriteTokens = add(cacheWriteTokens, other.cacheWriteTokens),
        reasoningTokens = add(reasoningTokens, other.reasoningTokens),
    )

    private fun add(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    companion object {
        val Unknown = Usage()
    }
}
