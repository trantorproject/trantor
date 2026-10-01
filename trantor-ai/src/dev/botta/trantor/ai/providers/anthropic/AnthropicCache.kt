package dev.botta.trantor.ai.providers.anthropic

/**
 * Which parts of the prompt Anthropic is asked to cache.
 *
 * Unlike OpenAI, which caches on its own, Anthropic caches only up to a mark, and a later call reads the cache
 * only if everything up to that mark is exactly the same. So what matters is where the marks go, and each flag
 * here puts one in a different place. They are independent and they add up:
 *
 * - [system] marks the end of the system prompt. It pays off when the same long system prompt comes before a
 *   different question every time.
 * - [tools] marks the last tool. It pays off when the tools stay the same and the system prompt does not.
 * - [conversation] is Anthropic's own mark, on the last block, moving forward as the conversation grows. It pays
 *   off when each call repeats the one before and adds a turn.
 *
 * Anthropic reads the tools first, then the system prompt, then the messages. So a mark on the system prompt caches
 * the tools too, and [tools] is only worth it on its own when the system prompt changes on every call. What changes
 * on every call, like today's date, is better in the `dynamicSystem` of the request, and the marks leave it out: the
 * one of the system prompt goes before it when it goes under the system prompt, and the one of the conversation goes
 * on the block before it when it goes last.
 *
 * **[conversation] alone does not cache a shared prompt.** The mark lands on the last block, which in a one-off
 * question is the question itself: new every time, so every call writes the whole system prompt and none reads it
 * back. With [system], the second call reads it back and pays the plain price only for the question. Pair the two
 * for an agent or a chat whose prompt is long:
 *
 * ```kotlin
 * anthropic.cache = AnthropicCache(system = true, conversation = true)
 * ```
 *
 * Or in the configuration, under `ai.providers.anthropic`:
 *
 * ```json
 * "cache": { "system": true, "conversation": true }
 * ```
 *
 * Everything is off by default, because a cache write costs more than a plain call (1.25 times the input for five
 * minutes, twice for an hour) and only pays off when it is read back. Below the model's minimum — somewhere between
 * 512 and 4,096 tokens, depending on the model — nothing is cached and nothing fails either.
 *
 * Anthropic takes four marks per request, and these use at most three. A cut somewhere else is marked on the part
 * it goes after, with `cache_control` in its `ProviderMetadata`; past four marks the call is rejected.
 */
data class AnthropicCache(
    /** Marks the end of the system prompt. Nothing to mark when the call has none. */
    val system: Boolean = false,
    /** Marks the last tool. Nothing to mark when the call has none. */
    val tools: Boolean = false,
    /** Anthropic's automatic mark, on the last block of the request, or the one before the dynamic system part. */
    val conversation: Boolean = false,
    /** How long every mark keeps what it cached. An hour costs more to write. */
    val ttl: AnthropicCacheTtl = AnthropicCacheTtl.FiveMinutes,
) {
    companion object {
        val Off = AnthropicCache()
    }
}

/** How long Anthropic keeps what it cached. An hour costs more to write than five minutes. */
enum class AnthropicCacheTtl { FiveMinutes, OneHour }
