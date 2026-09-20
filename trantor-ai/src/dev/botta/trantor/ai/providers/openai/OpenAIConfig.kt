package dev.botta.trantor.ai.providers.openai

import dev.botta.env.Env

data class OpenAIConfig(
    val apiKey: String = Env.getOrThrow("OPENAI_API_KEY"),
    val baseUrl: String = "https://api.openai.com/v1",
    val organization: String? = null,
    val project: String? = null,
    /**
     * Whether OpenAI keeps the response on its side, where it can be read from the dashboard for 30 days.
     * Null leaves OpenAI's own default. Trantor sends the whole conversation on every turn, so storing buys
     * nothing here beyond being able to look a call up later.
     */
    val store: Boolean? = null,
)
