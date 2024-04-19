package dev.botta.trantor.serialization.gson

import com.google.gson.*
import dev.botta.time.*
import java.lang.reflect.Type
import java.time.LocalDate

class LocalDateSerializer: JsonSerializer<LocalDate?>, JsonDeserializer<LocalDate?> {
    override fun serialize(localDate: LocalDate?, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(localDate?.formatAsISO8601())
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): LocalDate {
        return LocalDateParser().parseISO8601(json.asString)
    }
}
