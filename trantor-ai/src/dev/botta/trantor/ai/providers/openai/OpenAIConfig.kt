package dev.botta.trantor.ai.providers.openai

import dev.botta.env.Env

data class OpenAIConfig(
    val apiKey: String = apiKeyFromEnvironment(),
    val baseUrl: String = DEFAULT_BASE_URL,
    val organization: String? = null,
    val project: String? = null,
    /**
     * Whether OpenAI keeps the response on its side, where it can be read from the dashboard for 30 days.
     * Null leaves OpenAI's own default. Trantor sends the whole conversation on every turn, so storing buys
     * nothing here beyond being able to look a call up later.
     */
    val store: Boolean? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /**
         * Empty when the variable is not there. A missing key is not a reason to fail while the application is
         * starting: it only matters to whoever calls an OpenAI model, and they get told when they do.
         */
        fun apiKeyFromEnvironment() = Env["OPENAI_API_KEY"] ?: ""
    }
}
