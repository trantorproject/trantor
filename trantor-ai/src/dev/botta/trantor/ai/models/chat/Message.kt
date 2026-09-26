package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.providers.ProviderMetadata

sealed interface Message {
    data class System(val text: String): Message

    data class User(val parts: List<Part>): Message

    data class Assistant(
        val parts: List<Part>,
        /**
         * The agent that wrote it, when an agent did; null for a generation. It lets an agent tell its own turns
         * from those of another agent of its team, so the application keeps it with the message, run after run.
         */
        val agent: String? = null,
    ): Message

    data class Tool(val results: List<ToolResultPart>): Message

    /**
     * What stands for the old part of the conversation, in its place: a summary of it, so that what is kept and what
     * is sent do not grow without end. It goes first, after the system messages, and no [ContextPolicy]
     * [dev.botta.trantor.ai.history.ContextPolicy] cuts it.
     *
     * [text] is the summary as it can be read. A provider that writes its own keeps in [metadata] what it needs to
     * read it back, as it does with reasoning, and one that writes it opaque leaves [text] null: then only that
     * provider can read it, and any other leaves it out with a warning.
     *
     * A provider without a summary of its own reads it as something the user tells, after [PREAMBLE].
     */
    data class Summary(val text: String?, val metadata: ProviderMetadata = ProviderMetadata.None): Message {
        companion object {
            const val PREAMBLE = "For context, this is a summary of the earlier part of the conversation, which was " +
                "left out to save space:"
        }
    }

    companion object {
        fun system(text: String) = System(text)

        fun user(text: String) = User(listOf(TextPart(text)))

        fun assistant(text: String) = Assistant(listOf(TextPart(text)))

        fun toolResult(result: ToolResultPart) = Tool(listOf(result))
    }
}

/** The summary as a provider without one of its own reads it: told by the user, or nothing when it has no text. */
internal fun Message.Summary.toldByTheUser() =
    text?.let { Message.User(listOf(TextPart(Message.Summary.PREAMBLE), TextPart(it))) }

/** What an adapter warns when it leaves out a summary it cannot read. */
internal const val UNREADABLE_SUMMARY = "A summary with no text to read was left out, so the model does not have it"
