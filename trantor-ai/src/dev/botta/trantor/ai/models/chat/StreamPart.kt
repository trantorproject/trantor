package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonValue

/**
 * What a stream of a model hands over: pieces of text, reasoning or notes as they are written, and each part once
 * it is whole.
 */
sealed interface StreamPart {
    data class TextDelta(val text: String): StreamPart

    data class ReasoningDelta(val text: String): StreamPart

    /** A piece of a note the model writes between tool calls for whoever watches the run (see [ReasoningPart.note]). */
    data class NoteDelta(val text: String): StreamPart

    /** An item that finished: a text, a reasoning block or a tool call, already assembled. */
    data class PartDone(val part: Part): StreamPart

    /** An event the adapter does not map. It is not dropped: the caller can read it. */
    data class Raw(val event: String, val data: JsonValue): StreamPart
}
