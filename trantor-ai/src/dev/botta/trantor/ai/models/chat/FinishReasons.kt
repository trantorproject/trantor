package dev.botta.trantor.ai.models.chat

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
