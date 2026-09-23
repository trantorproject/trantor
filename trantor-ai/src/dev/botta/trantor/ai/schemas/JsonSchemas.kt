package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.encodeToString
import kotlinx.serialization.descriptors.SerialDescriptor
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

    fun of(type: KType): JsonObject = of(serializer(type).descriptor)

    fun of(descriptor: SerialDescriptor): JsonObject {
        val generated = SerializationClassJsonSchemaGenerator.Default.generateSchema(descriptor)
        val schema = Json.parse(generated.encodeToString()).asObject()
            ?: error("Could not generate a schema for ${descriptor.serialName}")

        // Metadata of the schema itself, which providers don't take
        schema.remove($$"$schema")
        schema.remove($$"$id")

        // A type without properties comes without the key, which strict mode needs to list them all as required:
        // the args of a tool that takes none, like the ones handoffs turn into
        if (schema["type"]?.asString() == "object" && !schema.containsKey("properties")) schema["properties"] = Json.obj()

        return schema
    }
}
