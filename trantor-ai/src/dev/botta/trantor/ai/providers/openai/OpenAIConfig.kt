package dev.botta.trantor.ai.providers.openai

import dev.botta.env.Env

data class OpenAIConfig(
    val apiKey: String = Env.getOrThrow("OPENAI_API_KEY"),
    val baseUrl: String = "https://api.openai.com/v1",
    val organization: String? = null,
    val project: String? = null,
)
