package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import java.lang.reflect.Type

/**
 * A value read from a string with [factory] and written as one with [serialize], like a key or a code. Its [schema] is
 * a string, unless it is given one that says more, like a `pattern` or a `format`.
 */
class StringValueSerializer<T: Any?>(
    private val factory: (String) -> T,
    private val serialize: (T) -> String = { it.toString() },
    val schema: JsonObject = Json.obj("type" to "string"),
): JsonSerializer<T>, JsonDeserializer<T> {
    override fun serialize(value: T, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(serialize(value))
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): T {
        val text = json.asString
        return readValue(text, (typeOfT as? Class<*>)?.simpleName ?: "value") { factory(text) }
    }
}
