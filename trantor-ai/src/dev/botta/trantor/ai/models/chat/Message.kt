package dev.botta.trantor.ai.models.chat

sealed interface Message {
    data class System(val text: String): Message

    data class User(val parts: List<Part>): Message

    data class Assistant(val parts: List<Part>): Message

    data class Tool(val results: List<ToolResultPart>): Message

    companion object {
        fun system(text: String) = System(text)

        fun user(text: String) = User(listOf(TextPart(text)))

        fun assistant(text: String) = Assistant(listOf(TextPart(text)))

        fun toolResult(result: ToolResultPart) = Tool(listOf(result))
    }
}
