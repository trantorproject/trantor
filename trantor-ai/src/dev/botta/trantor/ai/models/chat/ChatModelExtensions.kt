package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.models.CallOptions

/**
 * Shortcuts for the usual calls. They are extensions and not members of [ChatModel] so that writing an adapter
 * stays a matter of two methods.
 */

/** One call with [prompt] as the message of the user. */
fun ChatModel.generate(
    prompt: String,
    options: CallOptions = CallOptions(),
    settings: ChatSettings.() -> Unit = {},
) = generate(ChatRequest(listOf(Message.user(prompt)), settings = ChatSettings().apply(settings)), options)

/** One call with [messages] as the conversation. */
fun ChatModel.generate(vararg messages: Message) = generate(ChatRequest(messages.toList()))

/** One call with [prompt] as the message of the user, answered as it is written. */
fun ChatModel.stream(
    prompt: String,
    options: CallOptions = CallOptions(),
    settings: ChatSettings.() -> Unit = {},
) = stream(ChatRequest(listOf(Message.user(prompt)), settings = ChatSettings().apply(settings)), options)

/** One call with [messages] as the conversation, answered as it is written. */
fun ChatModel.stream(vararg messages: Message) = stream(ChatRequest(messages.toList()))

/** Only the text as it arrives, for whoever just wants to print the answer. */
fun ChatStream.textDeltas() = asSequence().filterIsInstance<StreamPart.TextDelta>().map { it.text }
