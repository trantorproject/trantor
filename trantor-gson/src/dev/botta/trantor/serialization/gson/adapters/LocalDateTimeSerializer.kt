package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import dev.botta.time.*
import java.lang.reflect.Type
import java.time.LocalDateTime

class LocalDateTimeSerializer: JsonSerializer<LocalDateTime?>, JsonDeserializer<LocalDateTime?> {
    override fun serialize(localDateTime: LocalDateTime?, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(localDateTime?.formatAsISO8601())
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): LocalDateTime {
        return LocalDateTimeParser().parseISO8601(json.asString)
    }
}
