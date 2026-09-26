package dev.botta.trantor.ai.providers.anthropic

import dev.botta.env.Env

/** Read from the `ai.providers.anthropic` section, and changed from `addAnthropic { config, services -> ... }`. */
data class AnthropicConfig(
    /**
     * Empty when it is nowhere to be found. A missing key is not a reason to fail while the application is
     * starting: it only matters to whoever calls an Anthropic model, and they get told when they do.
     */
    var apiKey: String = Env["ANTHROPIC_API_KEY"] ?: "",
    var baseUrl: String = DEFAULT_BASE_URL,
    /**
     * The `anthropic-version` header, which the api requires on every call. It is pinned rather than left to the
     * server so that an answer never changes shape underneath an application that did not ask for it.
     */
    var version: String = DEFAULT_VERSION,
    /**
     * Features still behind a flag, sent as `anthropic-beta`. They are named here and not in the code so that
     * turning one on does not need a release of Trantor.
     */
    var betas: List<String> = emptyList(),
    /**
     * What goes as `max_tokens` when a call does not set `ChatSettings.maxOutputTokens`. The api requires the
     * field, unlike every other provider, so there always has to be a number to send.
     *
     * Null means the most the model can give, which is what the model was asked for. Set it to put a ceiling on
     * every call of the application without writing it at each call site.
     */
    var defaultMaxTokens: Int? = null,
    /**
     * Which parts of the prompt to ask Anthropic to cache: the system prompt, the tools, the conversation, or any of
     * them together. Off by default, because a cache write costs more than a plain call; see [AnthropicCache] for
     * which one pays off when.
     */
    var cache: AnthropicCache = AnthropicCache.Off,
    /**
     * The longest a call waits for the model without receiving a byte, in milliseconds. A model can think for a
     * while before its first word, so this is longer than what the http client of the application waits for
     * anything else. It is set on every call, and the client is shared all the same.
     */
    var readTimeout: Int = DEFAULT_READ_TIMEOUT,
) {
    companion object {
        const val DEFAULT_READ_TIMEOUT = 120_000
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_VERSION = "2023-06-01"
    }
}
