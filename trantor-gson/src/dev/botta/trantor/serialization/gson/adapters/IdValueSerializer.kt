package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import dev.botta.trantor.domain.Id
import java.lang.reflect.Type

class IdValueSerializer: JsonSerializer<Id>, JsonDeserializer<Id> {
    override fun serialize(value: Id, srcType: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(value.toString())
    }

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): Id {
        return Id(json.asString)
    }
}
