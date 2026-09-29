package dev.botta.trantor.primitives.serialization

import dev.botta.json.values.JsonObject
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A serializer that can say, as a JSON Schema, what it reads for a type: what a model has to send for a tool whose
 * arguments it reads, or what an API documents for a request. The schema comes from the same rules that read the
 * JSON, so the two cannot drift apart.
 */
interface JsonSchemaSource {
    /**
     * The JSON Schema of what is read as [type]: the root inline, and the classes it uses in `$defs`.
     *
     * @throws JsonSchemaError when something in the type is read in a way the serializer cannot describe, like an
     * adapter registered without its schema, saying where it is.
     */
    fun schemaOf(type: KType): JsonObject
}

inline fun <reified T> JsonSchemaSource.schemaOf() = schemaOf(typeOf<T>())
