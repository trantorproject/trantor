package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import java.lang.reflect.Type
import java.time.LocalTime

class LocalTimeSerializer: JsonSerializer<LocalTime?>, JsonDeserializer<LocalTime?> {
    override fun serialize(localTime: LocalTime?, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(localTime?.toString())
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): LocalTime {
        return LocalTime.parse(json.asString)
    }
}
