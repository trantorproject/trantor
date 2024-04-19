package dev.botta.trantor.serialization.gson

import com.google.gson.*
import java.lang.reflect.Type

class StringValueSerializer<T: Any?>(
    private val factory: (String) -> T,
    private val serialize: (T) -> String = { it.toString() }
): JsonSerializer<T>, JsonDeserializer<T> {
    override fun serialize(value: T, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(serialize(value))
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): T {
        return factory(json.asString)
    }
}
