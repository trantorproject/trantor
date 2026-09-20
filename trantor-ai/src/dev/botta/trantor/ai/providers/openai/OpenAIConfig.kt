package dev.botta.trantor.ai.providers.openai

import dev.botta.env.Env

/** Read from the `ai.providers.openai` section, and changed from `addOpenAI { config, services -> ... }`. */
data class OpenAIConfig(
    /**
     * Empty when it is nowhere to be found. A missing key is not a reason to fail while the application is
     * starting: it only matters to whoever calls an OpenAI model, and they get told when they do.
     */
    var apiKey: String = Env["OPENAI_API_KEY"] ?: "",
    var baseUrl: String = DEFAULT_BASE_URL,
    var organization: String? = null,
    var project: String? = null,
    /**
     * Whether OpenAI keeps the response on its side, where it can be read from the dashboard for 30 days.
     * Null leaves OpenAI's own default. Trantor sends the whole conversation on every turn, so storing buys
     * nothing here beyond being able to look a call up later.
     */
    var store: Boolean? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
