package dev.botta.trantor.ai.serialization

import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * The serializer of a run that was given none, like a loop an application builds by hand: Gson with the adapters of
 * Trantor, for the types of the domain. One for all of them, since nothing registers on it.
 */
internal val defaultJsonSerializer: JsonSerializer by lazy { GsonSerializer() }

/**
 * [json] read as [type]. Gson takes the whole type, so a list is read with the type of what it holds; any other
 * serializer takes its class.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> JsonSerializer.read(json: String, type: KType): T =
    if (this is GsonSerializer) deserialize(json, type.javaType)
    else deserialize(json, (type.classifier as KClass<*>).java) as T

/**
 * The schema this serializer reads [type] by, for [what] asks for it, like a tool.
 *
 * @throws JsonSchemaError when the serializer is not one that can tell it, or it cannot tell it for [type].
 */
internal fun JsonSerializer.schemaFor(type: KType, what: String): JsonObject {
    val source = this as? JsonSchemaSource ?: throw JsonSchemaError(
        "$what needs the schema of $type, and ${this::class.simpleName} cannot tell it: the serializer of the run " +
            "has to be a JsonSchemaSource, like the GsonSerializer",
    )

    return source.schemaOf(type)
}
