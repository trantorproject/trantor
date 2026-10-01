package dev.botta.trantor.ai.models.chat

/**
 * Why the model stopped, the same for every provider. What the provider said is in
 * [ChatResponse.rawFinishReason].
 */
enum class FinishReasons {
    Stop,
    Length,
    ToolCalls,
    ContentFilter,
    Refusal,
    Error,
    // Something we do not map. The provider value stays in ChatResponse.rawFinishReason
    Other,
}
