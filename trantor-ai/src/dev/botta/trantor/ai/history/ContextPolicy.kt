package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.chat.Message

/**
 * Decides what part of the conversation goes to the model on each call, without touching the conversation the
 * application keeps: the history stays whole, and this is only what one call sees of it. It is asked before every
 * call, a run with tools included, with the whole conversation so far.
 *
 * ```kotlin
 * agents.run(support, Message.user(text)) {
 *     session(session)
 *     contextPolicy(DropOldToolResults(keep = 10), LastMessages(40))
 * }
 * ```
 *
 * Several go in the order they are given, each one getting what the one before sent. The system messages the
 * conversation starts with, and the instructions of an agent, are not theirs to cut: they always go, and a policy
 * gets the rest. In a run of agents, the turns of the other agents come already told as context with their names.
 *
 * A policy can leave a call without its result or a result without its call. The loop takes the half left alone
 * out, with a warning, since the providers reject it.
 *
 * What goes first is what the providers cache, so a policy that changes it on every call pays the whole input every
 * time. The ones of Trantor cut in steps, and a policy of the application should too. On the Claude models that tie
 * their thinking to what came before it, a cut costs the thinking of the turns it kept, which the adapter leaves out.
 *
 * Other libraries call this memory and cut what they keep (LangChain4j's and Spring's windows, Microsoft's reducers);
 * this one follows PydanticAI's history processors and OpenAI Agents' model input filter, which only change what the
 * model sees.
 */
fun interface ContextPolicy {
    fun project(messages: List<Message>, run: RunContext): List<Message>
}

/** Sends the whole conversation, which is what happens without a policy. */
object KeepAll: ContextPolicy {
    override fun project(messages: List<Message>, run: RunContext) = messages
}

/** [policies] in order, on what the conversation has after the system messages it starts with, which always go. */
internal fun projected(policies: List<ContextPolicy>, messages: List<Message>, run: RunContext): List<Message> {
    if (policies.isEmpty()) return messages

    val leading = messages.takeWhile { it is Message.System }

    return leading + policies.fold(messages.drop(leading.size)) { sent, policy -> policy.project(sent, run) }
}
