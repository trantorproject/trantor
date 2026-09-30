package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.search.ClientToolSearch

/**
 * The conversation of a request to the Messages API: the `system` field, the `messages` and the mark of the
 * conversation cache. It is the part shaped the most unlike OpenAI — the system prompt is a field and not a message,
 * the roles have to alternate, a tool result is a turn of the user — and the one where what a model takes changes
 * where things go, so it lives apart from the settings.
 *
 * Where the dynamic part of the instructions goes depends on the model, and is decided once, when the conversation
 * is made (see [DynamicPlacement]), so that nothing after asks again.
 *
 * One conversation belongs to one mapping, and remembers what it wrote: where the dynamic part went, for
 * [markCache], the thinking it sent, for [bindThinking], and the [betas] and the [stamp] that come out of it.
 */
internal class AnthropicConversation(
    private val takes: WhatTheModelTakes,
    private val warnings: MappingWarnings,
    private val cache: AnthropicCache,
    private val modelId: String,
    /** The search of the application among the tools of the request, whose answers go back as tool references. */
    private val search: ClientToolSearch = ClientToolSearch(emptyList()),
) {
    private val placement = when {
        takes.boundThinking -> LastForOneTurn
        takes.midConversationSystem -> Last
        else -> UnderTheSystemPrompt
    }
    private val thinking = if (takes.boundThinking) BoundThinking(modelId, warnings) else null
    private var dynamic: String? = null
    private var dynamicLast: SystemMessage? = null
    private val takenBetas = mutableSetOf<String>()

    /** The beta headers that what was written needs. */
    val betas: Set<String> get() = takenBetas

    /** What the thinking of the answer has to remember, on a model that ties it (see [ThinkingStamp]). */
    val stamp: ThinkingStamp? get() = thinking?.stamp

    /** The `messages` and the `system` field of [body]. */
    fun writeTo(body: JsonObject, messages: List<Message>, dynamic: String?) {
        this.dynamic = dynamic
        dynamicLast = placement.last(dynamic)

        body["messages"] = Json.array(toMessages(messages))
        systemOf(messages, placement.underTheSystemPrompt(dynamic))?.let { body["system"] = it }
    }

    /**
     * On a model that ties its thinking, leaves out what Anthropic would refuse and learns what the thinking of the
     * answer has to remember (see [BoundThinking]). It needs the system prompt and the tools, so it goes once both
     * are in [body].
     */
    fun bindThinking(body: JsonObject) {
        thinking?.bind(body, dynamic)
    }

    /**
     * The mark of the conversation cache, which is Anthropic's own and lands on the last block. When the last block
     * is the dynamic part, that would cache what changes on every call and read it back never, so the mark goes on
     * the block before it instead.
     */
    fun markCache(body: JsonObject) {
        if (!cache.conversation) return

        if (dynamicLast == null) {
            body["cache_control"] = markOf(cache)
            return
        }

        val messages = body["messages"]!!.asArray()!!

        messages.getOrNull(messages.size - 2)?.asObject()?.get("content")?.asArray()?.lastOrNull()?.asObject()
            ?.set("cache_control", markOf(cache))
    }

    /**
     * The system field: the system prompt, and under it the dynamic part when the model cannot take it after the
     * conversation. The mark of the system cache goes on the system prompt alone, so a dynamic part that changes
     * does not undo it. A single block with nothing to mark goes as the plain string.
     */
    private fun systemOf(messages: List<Message>, dynamic: String?): JsonValue? {
        val blocks = listOfNotNull(
            systemPromptOf(messages)?.let { prompt ->
                textBlock(prompt).also { if (cache.system) it["cache_control"] = markOf(cache) }
            },
            dynamic?.let { textBlock(it) },
        )

        return when {
            blocks.isEmpty() -> null
            blocks.size == 1 && !blocks[0].containsKey("cache_control") -> blocks[0]["text"]
            else -> Json.array(blocks)
        }
    }

    /**
     * The system prompt is a field of the request and not a message, so the first one ends up there wherever it
     * was written. The rest stay where they were on a model that takes a system message in the middle, which keeps
     * the cached prefix intact, and are joined into the field on one that does not.
     */
    private fun systemPromptOf(messages: List<Message>): String? {
        val texts = messages.filterIsInstance<Message.System>().map { it.text }

        if (takes.midConversationSystem) return texts.firstOrNull()

        if (texts.size > 1) {
            warnings.add(
                ModelWarning(
                    "$modelId does not take system messages in the middle of the conversation, " +
                        "so the ${texts.size} of them were joined into the system prompt${warnings.becauseItIsAGuess}",
                )
            )
        }

        return texts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Anthropic wants the roles to alternate, so two messages of the same role in a row become one message with
     * the content of both. A conversation built turn by turn — an assistant message and the reasoning that came
     * with it, a tool result after another — is otherwise rejected before the model sees it.
     *
     * The dynamic part, where it goes last, goes after all of it, so that what changes from one call to the next
     * comes after what repeats. And an earlier one the placement puts back goes right before the answer it preceded.
     */
    private fun toMessages(messages: List<Message>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val firstSystem = messages.indexOfFirst { it is Message.System }

        messages.forEachIndexed { index, message ->
            if (message is Message.Assistant) placement.before(message)?.let { addSystem(result, it) }
            if (message is Message.Summary && message.text == null) {
                warnings.add(ModelWarning(UNREADABLE_SUMMARY))
                return@forEachIndexed
            }

            val role = roleOf(message, isTheSystemPrompt = index == firstSystem) ?: return@forEachIndexed

            append(result, role, toContent(message))
        }

        dynamicLast?.let { addSystem(result, it) }

        return result.onEach { toolResultsFirst(it) }
    }

    /**
     * A message that lasts one turn goes on its own even after another system message: joined to it, its `clear_at`
     * would turn the other one off too.
     */
    private fun addSystem(result: MutableList<JsonObject>, message: SystemMessage) {
        if (!message.forOneTurn) return append(result, "system", listOf(textBlock(message.text)))

        takenBetas.add(CLEAR_AT_BETA)
        result.add(
            Json.obj(
                "role" to "system",
                "content" to Json.array(textBlock(message.text)),
                "clear_at" to "next_user_message",
            )
        )
    }

    private fun append(result: MutableList<JsonObject>, role: String, content: List<JsonObject>) {
        if (content.isEmpty()) return

        val last = result.lastOrNull()

        if (last?.get("role")?.asString() == role) {
            content.forEach { last["content"]!!.asArray()!!.add(it) }
        } else {
            result.add(Json.obj("role" to role, "content" to Json.array(content)))
        }
    }

    /**
     * Anthropic refuses a user message that has anything before its tool results — *"tool_use ids were found
     * without tool_result blocks immediately after"* — so they are moved to the front. It is a rule about the wire
     * and not about the conversation, and the caller writing a question after an answer is reasonable.
     */
    private fun toolResultsFirst(message: JsonObject) {
        val content = message["content"]?.asArray() ?: return
        val (results, rest) = content.partition { it.asObject()?.get("type")?.asString() == "tool_result" }

        if (results.isEmpty() || rest.isEmpty()) return

        message["content"] = Json.array(results + rest)
    }

    /** Null for whatever went into the system field, which is not a message here. */
    private fun roleOf(message: Message, isTheSystemPrompt: Boolean) = when (message) {
        is Message.System -> if (isTheSystemPrompt || !takes.midConversationSystem) null else "system"
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
        // A tool result is something the model is told, so Anthropic reads it as a turn of the user
        is Message.Tool -> "user"
        // Told by the user, which joins it to the turn that follows
        is Message.Summary -> "user"
    }

    private fun toContent(message: Message): List<JsonObject> = when (message) {
        is Message.System -> listOf(textBlock(message.text))
        is Message.User -> message.parts.mapNotNull { toBlock(it) }
        is Message.Assistant -> message.parts.mapNotNull { toBlock(it) }
        is Message.Tool -> message.results.mapNotNull { toBlock(it) }
        is Message.Summary -> message.toldByTheUser()?.parts.orEmpty().mapNotNull { toBlock(it) }
    }

    private fun toBlock(part: Part): JsonObject? = when (part) {
        is TextPart -> withMetadata(textBlock(part.text), part)
        // The type travels in the metadata, so a tool Anthropic ran itself goes back as the block it was
        is ToolCallPart -> withMetadata(
            Json.obj(
                "type" to "tool_use",
                "id" to part.callId,
                "name" to part.toolName,
                "input" to part.input,
            ),
            part,
        )
        is ToolResultPart -> withMetadata(toToolResultBlock(part), part)
        // A block we did not model when we received it goes back exactly as it came
        is ProviderPart -> if (part.provider == ANTHROPIC_PROVIDER) part.raw else unsupportedPart(part)
        // Thinking is signed by whoever produced it, so only its own provider can take it back
        is ReasoningPart -> part.opaque?.takeIf { part.metadata[ANTHROPIC_PROVIDER] != null }
            ?.also { thinking?.sending(it, part) }
            ?: foreignReasoning()
        else -> unsupportedPart(part)
    }

    /**
     * A result has no room for the name of the tool: Anthropic matches it to its call by id alone. The output goes
     * as a string, which is the form every model takes; what the search of the application found goes as references
     * to the tools, which Anthropic loads (see [toolReferences]).
     */
    private fun toToolResultBlock(part: ToolResultPart): JsonObject {
        val json = Json.obj(
            "type" to "tool_result",
            "tool_use_id" to part.callId,
            "content" to (toolReferences(part) ?: when (val output = part.output) {
                is ToolOutput.Text -> Json.value(output.value)
                is ToolOutput.Json -> Json.value(output.value.toString())
            }),
        )

        if (part.isError) json["is_error"] = true

        return json
    }

    /**
     * What the search of the application found, as the tool references of a custom tool search: *"return a standard
     * tool_result with tool_reference blocks"* (tool-search-tool, "Custom tool search implementation", read on
     * 2026-09-30). Only on a model that searches tools, and only when it found something: an answer that found nothing
     * goes as it was written, with the names of the tools to search again with.
     */
    private fun toolReferences(part: ToolResultPart): JsonArray? {
        if (!takes.toolSearch || !search.isSearch(part.toolName)) return null

        val found = search.found(part).ifEmpty { return null }

        return Json.array(found.map { Json.obj("type" to "tool_reference", "tool_name" to it.name) })
    }

    private fun foreignReasoning(): JsonObject? {
        warnings.add(ModelWarning("Reasoning that Anthropic did not produce was dropped"))

        return null
    }

    /** Whatever the provider attached to the part travels back with it: citations and cache marks, for two. */
    private fun withMetadata(json: JsonObject, part: Part): JsonObject {
        val extras = part.metadata[ANTHROPIC_PROVIDER] ?: return json

        extras.keys.forEach { json[it] = extras.getValue(it) }

        return json
    }

    private fun unsupportedPart(part: Part): JsonObject? {
        warnings.add(ModelWarning("${part::class.simpleName} is not sent to Anthropic yet and was dropped"))

        return null
    }

    private fun textBlock(text: String) = Json.obj("type" to "text", "text" to text)
}

/**
 * Where the dynamic part of the instructions goes on a model. The part changes from one call to the next and the
 * providers cache the beginning of a request that repeats, so it goes as late as the model lets it.
 */
private sealed interface DynamicPlacement {
    /** The dynamic part under the system prompt, where the model takes a system message nowhere else. */
    fun underTheSystemPrompt(dynamic: String?): String? = null

    /** The dynamic part after the conversation. */
    fun last(dynamic: String?): SystemMessage? = null

    /** A copy of an earlier dynamic part, put back before the answer of the model it preceded. */
    fun before(answer: Message.Assistant): SystemMessage? = null
}

/** On a model that takes no system message in the middle, the best left is right under the system prompt. */
private object UnderTheSystemPrompt: DynamicPlacement {
    override fun underTheSystemPrompt(dynamic: String?) = dynamic
}

/** After the conversation, which keeps everything before it cached. */
private object Last: DynamicPlacement {
    override fun last(dynamic: String?) = dynamic?.let { SystemMessage(it, forOneTurn = false) }
}

/**
 * On a model that ties its thinking to what came before it, which Opus 5.5, Sonnet 5.5 and Fable 5.1 do.
 *
 * The dynamic part still goes last, but that moves it on every call: the thinking of an answer was produced with it
 * right before, and on the next call it is not there anymore. Anthropic reads that as a change before the thinking
 * and answers 400. So the part goes as a message that lasts one turn (`clear_at`), the thinking of the answer
 * remembers it (see [ThinkingStamp]), and on the next call the copy is put back right before the answer: a user
 * message came after it, so it is cleared, reads as nothing and costs nothing, and what came before the thinking is
 * exactly what it was. That is how Anthropic asks for it — the conversation only grows at its end — while the dynamic
 * part never becomes a message of the conversation.
 */
private object LastForOneTurn: DynamicPlacement {
    override fun last(dynamic: String?) = dynamic?.let { SystemMessage(it, forOneTurn = true) }

    override fun before(answer: Message.Assistant) =
        ThinkingStamp.dynamicSystemOf(answer)?.let { SystemMessage(it, forOneTurn = true) }
}

/** A system message in the middle of the conversation, which with [forOneTurn] clears when a user message follows. */
private data class SystemMessage(val text: String, val forOneTurn: Boolean)

/** Turn-scoped system messages, the ones with `clear_at`, are a beta. */
private const val CLEAR_AT_BETA = "mid-conversation-system-clear-at-2026-08-21"

/** Every mark of a request has the same duration: Anthropic refuses a mark that outlives one before it. */
internal fun markOf(cache: AnthropicCache) = when (cache.ttl) {
    AnthropicCacheTtl.FiveMinutes -> Json.obj("type" to "ephemeral")
    AnthropicCacheTtl.OneHour -> Json.obj("type" to "ephemeral", "ttl" to "1h")
}
