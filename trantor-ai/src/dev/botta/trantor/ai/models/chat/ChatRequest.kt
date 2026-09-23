package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.tools.ToolChoice
import dev.botta.trantor.ai.tools.ToolSpec

data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val output: OutputSpec = OutputSpec.Text,
    val settings: ChatSettings = ChatSettings(),
    val providerOptions: ProviderOptions = ProviderOptions.None,
    /**
     * Instructions that change from one call to the next — the time, what the application knows about the user,
     * the state of a process — kept apart from the system prompt, which does not.
     *
     * Providers cache the beginning of a request that repeats, so what changes goes last: as a system message after
     * the conversation where the model takes one there, and under the system prompt where it does not. It is not a
     * message of the conversation, and is sent again on every call.
     */
    val dynamicSystem: String? = null,
) {
    constructor(vararg messages: Message): this(messages.toList())

    constructor(prompt: String): this(listOf(Message.user(prompt)))
}
