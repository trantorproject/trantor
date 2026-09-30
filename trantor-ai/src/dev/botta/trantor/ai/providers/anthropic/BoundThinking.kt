package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ReasoningPart
import java.security.MessageDigest

/**
 * The thinking of a request to a model that ties each thinking block to what came before it — the top-level
 * `system`, the `tools` and every earlier message — which Opus 5.5, Sonnet 5.5 and Fable 5.1 do. Anthropic answers 400
 * to a block whose prefix changed.
 *
 * Within a conversation that only grows the prefix never changes, but some things change the past:
 *
 * - a call goes out with another system prompt or other tools: the application changed the instructions of an agent
 *   between turns, a generation goes on with other tools, or an agent took over a history whose answers are not
 *   signed by their agent (signed ones reach it as told context, without their thinking);
 * - a context policy sends less than the whole conversation: it took the oldest messages out, or shortened old tool
 *   results.
 *
 * The thinking produced before such a change would be refused. So each block remembers a fingerprint of what came
 * before it ([ThinkingStamp]), and a request leaves out every block up to the last one whose prefix is not what it
 * remembers: Anthropic takes thinking left out from the start of the conversation, not from its middle, so the
 * blocks after it stay valid and keep their reasoning. The model loses the older reasoning, as it would with another
 * model.
 *
 * Anthropic's own way out, `drop_block`, drops the failing block and every one after it, which would be all the
 * reasoning produced since, on every call from then on.
 */
internal class BoundThinking(private val modelId: String, private val warnings: MappingWarnings) {
    private val sent = mutableListOf<Pair<JsonObject, String?>>()

    /** What the thinking of the answer has to remember, known once [bind] saw the request. */
    var stamp: ThinkingStamp? = null
        private set

    /** A thinking block on its way, and the fingerprint of what came before it, if it remembers one. */
    fun sending(block: JsonObject, part: ReasoningPart) {
        sent.add(block to ThinkingStamp.prefixOf(part))
    }

    /** Leaves out the thinking Anthropic would refuse, once the system prompt, tools and messages are written. */
    fun bind(body: JsonObject, dynamic: String?) {
        val messages = body["messages"]?.asArray()?.mapNotNull { it.asObject() } ?: emptyList()
        val prefix = Prefix(body)
        // What comes before each block sent, now: the prefix up to the message it is in
        val now = arrayOfNulls<String>(sent.size)

        messages.forEach { message ->
            val before = prefix.fingerprint()

            message["content"]?.asArray()?.forEach { block ->
                sent.indexOfFirst { it.first === block }.takeIf { it >= 0 }?.let { now[it] = before }
            }
            prefix.add(message)
        }

        stamp = ThinkingStamp(dynamic, prefix.fingerprint())

        val lastStale = sent.indices.lastOrNull { i -> sent[i].second.let { it != null && it != now[i] } } ?: return
        val stale = sent.take(lastStale + 1).map { it.first }

        messages.forEach { message ->
            val content = message["content"]?.asArray() ?: return@forEach
            message["content"] = Json.array(content.filterNot { block -> stale.any { it === block } })
        }

        warnings.add(
            ModelWarning(
                "Thinking produced after a system prompt, tools or messages that have changed since was left out: " +
                    "$modelId ties each thinking block to what came before it, and would refuse it",
            )
        )
    }

    /**
     * A running fingerprint of what comes before a point of the request: the system prompt, the tools and the
     * messages so far. It leaves out what a block is not tied to: the cache marks, which can move, and the thinking,
     * which can be left out from the start. The dynamic part goes as a message on these models, so it is in it, as
     * the copy put back before each answer.
     */
    private class Prefix(body: JsonObject) {
        private val digest = MessageDigest.getInstance("SHA-256")

        init {
            add(Json.obj("system" to (body["system"] ?: Json.value("")), "tools" to (body["tools"] ?: Json.array())))
        }

        fun add(value: JsonObject) {
            digest.update(tied(value).toString().toByteArray())
            digest.update(SEPARATOR)
        }

        fun fingerprint() = (digest.clone() as MessageDigest).digest().take(16).joinToString("") { "%02x".format(it) }

        private fun tied(value: JsonValue): JsonValue = when (value) {
            is JsonObject -> Json.obj().also { copy ->
                value.keys.filter { it != "cache_control" }.forEach { copy[it] = tied(value.getValue(it)) }
            }
            is JsonArray -> Json.array(value.filterNot(::isThinking).map { tied(it) })
            else -> value
        }

        private fun isThinking(block: JsonValue) =
            block.asObject()?.get("type")?.asString().let { it == "thinking" || it == "redacted_thinking" }

        private companion object {
            val SEPARATOR = byteArrayOf('\n'.code.toByte())
        }
    }
}

/**
 * What a thinking block of a model that ties its thinking has to remember, kept in the metadata of its part: the
 * dynamic part it was produced with, so a later call can put it back where it was, and the fingerprint of what came
 * before it, so a later call knows whether Anthropic would still take it (see [BoundThinking]). The application
 * stores both with the rest of the part, as it stores the signature, without having to know what they are.
 *
 * A block stamped before the fingerprint took the messages in remembers only the system prompt and the tools, under
 * another key; it is sent as it came, as one that remembers nothing.
 */
internal data class ThinkingStamp(val dynamicSystem: String?, val prefix: String) {
    /** [metadata] of a thinking block, with what it has to remember. */
    fun on(metadata: JsonObject) = metadata.also {
        dynamicSystem?.let { dynamic -> it[DYNAMIC_SYSTEM] = dynamic }
        it[PREFIX] = prefix
    }

    companion object {
        private const val DYNAMIC_SYSTEM = "dynamic_system"
        private const val PREFIX = "prefix"

        /** The dynamic part the thinking of [answer] was produced with, if it was. */
        fun dynamicSystemOf(answer: Message.Assistant) = answer.parts.filterIsInstance<ReasoningPart>()
            .firstNotNullOfOrNull { it.metadata[ANTHROPIC_PROVIDER]?.get(DYNAMIC_SYSTEM)?.asString() }

        /** The fingerprint of what came before [part], if it remembers one. */
        fun prefixOf(part: ReasoningPart) = part.metadata[ANTHROPIC_PROVIDER]?.get(PREFIX)?.asString()
    }
}
