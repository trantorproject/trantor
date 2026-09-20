package dev.botta.trantor.ai.models.chat

data class ChatSettings(
    var maxOutputTokens: Int? = null,
    var temperature: Double? = null,
    var topP: Double? = null,
    var stopSequences: List<String>? = null,
    var seed: Long? = null,
    var reasoning: Reasoning? = null,
    var parallelToolCalls: Boolean? = null,
    /**
     * Whether something the adapter cannot honor fails the call instead of coming back as a `ModelWarning`.
     * Off by default: losing a setting is usually better than losing the answer.
     */
    var failOnWarnings: Boolean = false,
)
