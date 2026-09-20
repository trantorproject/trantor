package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.schemas.JsonSchemas

sealed interface OutputSpec {
    data object Text: OutputSpec

    data class Json(val schema: JsonObject, val name: String = "response", val strict: Boolean = true): OutputSpec

    companion object {
        /** The answer as a @Serializable type: its schema goes to the model and [objectAs] reads it back. */
        inline fun <reified T> json(name: String = "response", strict: Boolean = true) =
            Json(JsonSchemas.of<T>(), name, strict)
    }
}
