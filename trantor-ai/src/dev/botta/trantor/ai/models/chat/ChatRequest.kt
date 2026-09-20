package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.ProviderOptions
import dev.botta.trantor.ai.tools.ToolChoice
import dev.botta.trantor.ai.tools.ToolSpec

data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val output: OutputSpec = OutputSpec.Text,
    val settings: ChatSettings = ChatSettings(),
    val providerOptions: ProviderOptions = ProviderOptions.None,
) {
    constructor(vararg messages: Message): this(messages.toList())
}
