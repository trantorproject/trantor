package dev.botta.trantor.ai.providers.openai

data class OpenAIConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val organization: String? = null,
    val project: String? = null,
)
