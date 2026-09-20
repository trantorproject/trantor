package dev.botta.trantor.ai.models

import dev.botta.json.values.JsonObject

/**
 * Tokens reported by the provider.
 *
 * The detail fields are **subsets**, not addends: [cachedInputTokens] is part of [inputTokens] and [reasoningTokens]
 * is part of [outputTokens], no matter what each provider reports. Adapters normalize to this contract, so that
 * input + output is always the real total and nothing is counted twice.
 *
 * Null means unknown, which is not the same as zero: some providers don't report usage while streaming.
 */
data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
    // Usage as the provider sent it, for what we don't model yet (audio, images)
    val raw: JsonObject? = null,
) {
    val totalTokens get() = add(inputTokens, outputTokens)

    /** Adds up usages of several calls. Keeps no raw, since it belongs to a single call. */
    operator fun plus(other: Usage) = Usage(
        inputTokens = add(inputTokens, other.inputTokens),
        outputTokens = add(outputTokens, other.outputTokens),
        cachedInputTokens = add(cachedInputTokens, other.cachedInputTokens),
        cacheWriteTokens = add(cacheWriteTokens, other.cacheWriteTokens),
        reasoningTokens = add(reasoningTokens, other.reasoningTokens),
    )

    private fun add(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    companion object {
        val Unknown = Usage()
    }
}
