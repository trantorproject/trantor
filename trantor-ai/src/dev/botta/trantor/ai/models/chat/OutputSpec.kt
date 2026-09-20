package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonObject

sealed interface OutputSpec {
    data object Text: OutputSpec

    data class Json(val schema: JsonObject, val name: String = "response", val strict: Boolean = true): OutputSpec
}
