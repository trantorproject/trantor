package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonValue

sealed interface StreamPart {
    data class TextDelta(val text: String): StreamPart

    data class ReasoningDelta(val text: String): StreamPart

    /** An item that finished: a text, a reasoning block or a tool call, already assembled. */
    data class PartDone(val part: Part): StreamPart

    /** An event the adapter does not map. It is not dropped: the caller can read it. */
    data class Raw(val event: String, val data: JsonValue): StreamPart
}
