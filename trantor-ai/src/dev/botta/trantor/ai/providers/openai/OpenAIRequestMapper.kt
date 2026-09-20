package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.*

/** Turns a [ChatRequest] into the body of a call to the OpenAI Responses API. */
internal class OpenAIRequestMapper {
    private val warnings = mutableListOf<ModelWarning>()

    fun map(modelId: String, request: ChatRequest, stream: Boolean = false): MappedRequest {
        val body = Json.obj(
            "model" to modelId,
            "input" to Json.array(request.messages.flatMap { toItems(it) }),
        )

        if (stream) body["stream"] = true

        with(request.settings) {
            maxOutputTokens?.let { body["max_output_tokens"] = it }
            temperature?.let { body["temperature"] = it }
            topP?.let { body["top_p"] = it }
            stopSequences?.let { unsupportedSetting("stopSequences") }
            seed?.let { unsupportedSetting("seed") }
        }

        return MappedRequest(body, warnings.toList())
    }

    private fun toItems(message: Message): List<JsonObject> = when (message) {
        is Message.System -> listOf(Json.obj("type" to "message", "role" to "system", "content" to message.text))
        is Message.User -> toMessageItems("user", message.parts)
        is Message.Assistant -> toMessageItems("assistant", message.parts)
        is Message.Tool -> { message.results.forEach { unsupportedPart(it) }; emptyList() }
    }

    /**
     * Content parts travel inside a message item and everything else is an item of its own, keeping the order they
     * had in the message: a reasoning item has to reach OpenAI before the message it belongs to.
     */
    private fun toMessageItems(role: String, parts: List<Part>): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var content: JsonArray? = null

        parts.forEach { part ->
            when (part) {
                is TextPart -> {
                    if (content == null) {
                        content = Json.array()
                        items.add(Json.obj("type" to "message", "role" to role, "content" to content))
                    }
                    content.add(Json.obj("type" to textType(role), "text" to part.text))
                }
                // An item we didn't model when we received it goes back exactly as it came
                is ProviderPart -> {
                    content = null
                    if (part.provider == OpenAIResponsesModel.PROVIDER) items.add(part.raw) else unsupportedPart(part)
                }
                else -> unsupportedPart(part)
            }
        }

        return items
    }

    private fun textType(role: String) = if (role == "assistant") "output_text" else "input_text"

    private fun unsupportedPart(part: Part) {
        warnings.add(ModelWarning("${part::class.simpleName} is not sent to OpenAI yet and was dropped"))
    }

    private fun unsupportedSetting(setting: String) {
        warnings.add(ModelWarning("The OpenAI Responses API does not support $setting", setting))
    }
}

internal data class MappedRequest(val body: JsonObject, val warnings: List<ModelWarning>)
