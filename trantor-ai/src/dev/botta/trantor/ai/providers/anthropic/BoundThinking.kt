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
 * `system`, the `tools` and every earlier message — which Opus 5.5 and Fable 5.1 do. Anthropic answers 400 to a block
 * whose prefix changed.
 *
 * Within a conversation the prefix only grows, except when an agent hands the conversation over to another: the
 * next step goes out with other instructions and other tools, and the thinking of the one before would be refused.
 * So each block remembers a fingerprint of the system prompt and the tools it was produced under ([ThinkingStamp]),
 * and a request leaves out every block up to the last one produced under another: Anthropic takes thinking left out
 * from the start of the conversation, not from its middle, so the blocks after it stay valid and keep their
 * reasoning. The model loses the reasoning of the other agent, as it would with another model.
 *
 * Anthropic's own way out, `drop_block`, drops the failing block and every one after it, which after a handoff would
 * be all the reasoning of the new agent too.
 */
internal class BoundThinking(private val modelId: String, private val warnings: MappingWarnings) {
    private val sent = mutableListOf<Pair<JsonObject, String?>>()

    /** What the thinking of the answer has to remember, known once [bind] saw the request. */
    var stamp: ThinkingStamp? = null
        private set

    /** A thinking block on its way, and the fingerprint of what it was produced under, if it remembers one. */
    fun sending(block: JsonObject, part: ReasoningPart) {
        sent.add(block to ThinkingStamp.setupOf(part))
    }

    /** Leaves out the thinking Anthropic would refuse, once the system prompt and the tools of [body] are written. */
    fun bind(body: JsonObject, dynamic: String?) {
        val setup = setupOf(body)
        val lastStale = sent.indexOfLast { (_, producedUnder) -> producedUnder != null && producedUnder != setup }

        stamp = ThinkingStamp(dynamic, setup)

        if (lastStale < 0) return

        val stale = sent.take(lastStale + 1).map { it.first }

        body["messages"]?.asArray()?.forEach { message ->
            val content = message.asObject()?.get("content")?.asArray() ?: return@forEach
            message.asObject()!!["content"] = Json.array(content.filterNot { block -> stale.any { it === block } })
        }

        warnings.add(
            ModelWarning(
                "Thinking produced under another system prompt or other tools was left out: $modelId ties each " +
                    "thinking block to what came before it, and would refuse it",
            )
        )
    }

    /**
     * A fingerprint of the system prompt and the tools as they go, without the cache marks, which are not part of
     * what a block is tied to. The dynamic part goes as a message on these models, so it is not in it either.
     */
    private fun setupOf(body: JsonObject): String {
        val prefix = Json.obj("system" to (body["system"] ?: Json.value("")), "tools" to (body["tools"] ?: Json.array()))
        val digest = MessageDigest.getInstance("SHA-256").digest(withoutCacheMarks(prefix).toString().toByteArray())

        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun withoutCacheMarks(value: JsonValue): JsonValue = when (value) {
        is JsonObject -> Json.obj().also { copy ->
            value.keys.filter { it != "cache_control" }.forEach { copy[it] = withoutCacheMarks(value.getValue(it)) }
        }
        is JsonArray -> Json.array(value.map { withoutCacheMarks(it) })
        else -> value
    }
}

/**
 * What a thinking block of a model that ties its thinking has to remember, kept in the metadata of its part: the
 * dynamic part it was produced with, so a later call can put it back where it was, and the fingerprint of the system
 * prompt and the tools, so a later call knows whether Anthropic would still take it (see [BoundThinking]). The
 * application stores both with the rest of the part, as it stores the signature, without having to know what they are.
 */
internal data class ThinkingStamp(val dynamicSystem: String?, val setup: String) {
    /** [metadata] of a thinking block, with what it has to remember. */
    fun on(metadata: JsonObject) = metadata.also {
        dynamicSystem?.let { dynamic -> it[DYNAMIC_SYSTEM] = dynamic }
        it[SETUP] = setup
    }

    companion object {
        private const val DYNAMIC_SYSTEM = "dynamic_system"
        private const val SETUP = "setup"

        /** The dynamic part the thinking of [answer] was produced with, if it was. */
        fun dynamicSystemOf(answer: Message.Assistant) = answer.parts.filterIsInstance<ReasoningPart>()
            .firstNotNullOfOrNull { it.metadata[ANTHROPIC_PROVIDER]?.get(DYNAMIC_SYSTEM)?.asString() }

        /** The fingerprint of what [part] was produced under, if it remembers one. */
        fun setupOf(part: ReasoningPart) = part.metadata[ANTHROPIC_PROVIDER]?.get(SETUP)?.asString()
    }
}
