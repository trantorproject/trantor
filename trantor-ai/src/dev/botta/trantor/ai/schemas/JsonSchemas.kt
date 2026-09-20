package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.encodeToString
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * JSON Schemas out of Kotlin types, for tool arguments and structured output.
 *
 * The type has to be @Serializable: the schema comes from the same descriptor kotlinx.serialization uses to decode
 * the answer, so what the model is asked for and what is read back cannot drift apart.
 */
object JsonSchemas {
    inline fun <reified T> of(): JsonObject = of(typeOf<T>())

    fun of(type: KType): JsonObject {
        val descriptor = serializer(type).descriptor
        val generated = SerializationClassJsonSchemaGenerator.Default.generateSchema(descriptor)
        val schema = Json.parse(generated.encodeToString()).asObject() ?: error("Could not generate a schema for $type")

        // Metadata of the schema itself, which providers don't take
        schema.remove($$"$schema")
        schema.remove($$"$id")

        return schema
    }
}
