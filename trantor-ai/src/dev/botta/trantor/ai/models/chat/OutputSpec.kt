package dev.botta.trantor.ai.models.chat

import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import kotlin.reflect.typeOf

/** The shape of the answer: text, or JSON held to a schema where the model takes one. */
sealed interface OutputSpec {
    data object Text: OutputSpec

    data class Json(val schema: JsonObject, val name: String = "response", val strict: Boolean = true): OutputSpec

    companion object {
        /**
         * The answer as a [T], whose schema [serializer] tells the model and [objectAs] reads it back with: the
         * serializer of the application, with the types it registered.
         */
        inline fun <reified T> json(serializer: JsonSchemaSource, name: String = "response", strict: Boolean = true) =
            Json(serializer.schemaOf(typeOf<T>()), name, strict)
    }
}
