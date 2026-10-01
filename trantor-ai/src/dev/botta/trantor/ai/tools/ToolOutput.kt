package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonValue

/** What a tool answers: text, or JSON the model reads as written. */
sealed interface ToolOutput {
    data class Text(val value: String): ToolOutput

    data class Json(val value: JsonValue): ToolOutput
}
