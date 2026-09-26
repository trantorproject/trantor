package dev.botta.trantor.ai.telemetry

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.Part
import dev.botta.trantor.ai.models.chat.ProviderPart
import dev.botta.trantor.ai.models.chat.ReasoningPart
import dev.botta.trantor.ai.models.chat.RefusalPart
import dev.botta.trantor.ai.models.chat.TextPart
import dev.botta.trantor.ai.models.chat.ToolCallPart
import dev.botta.trantor.ai.models.chat.ToolResultPart
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.ProviderToolSpec
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.ToolSpec

/**
 * What was said, as JSON in the shape the conventions give it: the models of `docs/gen-ai/non-normative/models.py`
 * at the same commit as [GenAITelemetry]. The Java API has no structured attributes on spans yet, and for that case the
 * conventions ask for the JSON as a string.
 *
 * - The `System` messages and the dynamic instructions are the system instructions, and not part of the input.
 * - A turn of an agent carries its name, which the conventions call the name of the participant.
 * - Reasoning goes only when the model let it be read. What it signed or encrypted means nothing to a reader, and
 *   is never written.
 * - A part the conventions have no type for goes with a type of its own: a refusal with its text, and a part of a
 *   provider with its type alone, since its raw content may be anything.
 * - The definitions of the tools leave their parameters out, as the conventions recommend: they are the whole JSON
 *   Schema of the args.
 *
 * Every text longer than [maxLength] is cut and marked with `…`, so the JSON around it stays whole.
 */
internal class GenAIContent(private val maxLength: Int?) {
    fun systemInstructions(request: ChatRequest): String? {
        val parts = request.messages.filterIsInstance<Message.System>().map { text(it.text) } +
            listOfNotNull(request.dynamicSystem?.let { text(it) })

        return parts.takeIf { it.isNotEmpty() }?.let { Json.array(it).toString() }
    }

    fun inputMessages(request: ChatRequest) =
        Json.array(request.messages.mapNotNull { message(it) }).toString()

    fun outputMessages(response: ChatResponse, agent: String?) =
        Json.array(listOf(message("assistant", response.content, agent))).toString()

    fun toolDefinitions(tools: List<ToolSpec>): String? =
        tools.takeIf { it.isNotEmpty() }?.let { Json.array(it.map(::definition)).toString() }

    fun arguments(input: JsonObject) = input.toString()

    fun result(output: ToolOutput) = when (output) {
        is ToolOutput.Text -> cut(output.value)
        is ToolOutput.Json -> output.value.toString()
    }

    private fun message(message: Message): JsonObject? = when (message) {
        is Message.System -> null
        is Message.User -> message("user", message.parts, null)
        is Message.Assistant -> message("assistant", message.parts, message.agent)
        is Message.Tool -> message("tool", message.results, null)
    }

    private fun message(role: String, parts: List<Part>, name: String?) = Json.obj(
        listOfNotNull(
            "role" to role,
            "parts" to Json.array(parts.mapNotNull(::part)),
            name?.let { "name" to it },
        ),
    )

    private fun part(part: Part): JsonObject? = when (part) {
        is TextPart -> text(part.text)
        is ReasoningPart -> part.text?.let { Json.obj("type" to "reasoning", "content" to cut(it)) }
        is RefusalPart -> Json.obj("type" to "refusal", "content" to cut(part.text))
        is ToolCallPart -> if (part.providerExecuted) serverToolCall(part) else toolCall(part)
        is ToolResultPart -> Json.obj("type" to "tool_call_response", "id" to part.callId, "response" to response(part))
        is ProviderPart -> Json.obj("type" to part.type)
    }

    private fun toolCall(call: ToolCallPart) =
        Json.obj("type" to "tool_call", "id" to call.callId, "name" to call.toolName, "arguments" to call.input)

    private fun serverToolCall(call: ToolCallPart) = Json.obj(
        "type" to "server_tool_call",
        "id" to call.callId,
        "name" to call.toolName,
        "server_tool_call" to Json.obj("type" to call.toolName, "arguments" to call.input),
    )

    private fun response(result: ToolResultPart) = when (val output = result.output) {
        is ToolOutput.Text -> Json.value(cut(output.value))
        is ToolOutput.Json -> output.value
    }

    private fun definition(tool: ToolSpec) = when (tool) {
        is FunctionToolSpec -> Json.obj(
            listOfNotNull("type" to "function", "name" to tool.name, tool.description?.let { "description" to it }),
        )
        is ProviderToolSpec -> Json.obj("type" to tool.name, "name" to tool.name)
    }

    private fun text(text: String) = Json.obj("type" to "text", "content" to cut(text))

    private fun cut(text: String) =
        if (maxLength != null && text.length > maxLength) text.take(maxLength) + "…" else text
}
