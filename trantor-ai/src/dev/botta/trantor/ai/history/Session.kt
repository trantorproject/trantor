package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.models.chat.Message

/**
 * Where the conversation is kept between runs, so a run can go on from it without the application passing the
 * history in and keeping what came out: the run reads it before its first call to the model and, once it ended well,
 * adds what it got and what it added. A run that fails adds nothing.
 *
 * ```kotlin
 * val session = conversations.sessionOf(chatId)   // the application's, over its own tables
 * agents.run(support, Message.user(text)) { session(session) }
 * ```
 *
 * It is an interface so the application keeps the conversation where it keeps everything else. It has to keep each
 * message whole, the metadata of its parts and the agent of an answer included, since that is what lets a provider
 * pick up where it left off. [InMemorySession] keeps it in memory, for tests and prototypes.
 *
 * It reads the whole conversation, and how much goes to the model is up to a [ContextPolicy], which does not touch
 * what is kept. A session over a database can read only its end if it cuts in steps, as [LastMessages] does, so that
 * what goes first does not change on every call. What bounds how much a conversation keeps is a [Compaction], which
 * summarizes its old part and [replaces][replace] what the session keeps with it.
 *
 * Two runs on the same session at once read the same history and add each their own, one after the other. The
 * application runs the turns of a conversation one at a time.
 */
interface Session {
    /** The conversation so far. */
    fun load(): List<Message>

    /** Adds what a run that ended well got and added, in order. */
    fun append(messages: List<Message>)

    /**
     * Keeps [messages] in place of everything it kept: the conversation a [Compaction] left, with the old part in a
     * summary. What happens to what it kept before — deleted, or marked as replaced — is up to the application.
     */
    fun replace(messages: List<Message>)
}

/** A [Session] in memory, which dies with the process. */
class InMemorySession(messages: List<Message> = emptyList()): Session {
    private val messages = messages.toMutableList()

    @Synchronized
    override fun load() = messages.toList()

    @Synchronized
    override fun append(messages: List<Message>) {
        this.messages.addAll(messages)
    }

    @Synchronized
    override fun replace(messages: List<Message>) {
        this.messages.clear()
        this.messages.addAll(messages)
    }
}
