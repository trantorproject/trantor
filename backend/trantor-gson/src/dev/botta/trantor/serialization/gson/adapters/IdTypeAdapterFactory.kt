package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.*
import dev.botta.trantor.domain.Id
import java.util.*

class IdTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType

        // Ignore if not Id subclass
        if (!Id::class.java.isAssignableFrom(raw)) return null

        @Suppress("UNCHECKED_CAST")
        val idClass = raw as Class<out Id>

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                val id = value as Id
                out.value(id.toString())
            }

            @Suppress("UNCHECKED_CAST")
            override fun read(`in`: JsonReader): T {
                val str = `in`.nextString()
                val ctor = idClass.getConstructor(UUID::class.java)
                return ctor.newInstance(UUID.fromString(str)) as T
            }
        }
    }
}
