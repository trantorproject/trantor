package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.*
import dev.botta.trantor.core.lang.Maybe
import java.lang.reflect.*

class MaybeTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != Maybe::class.java) return null

        val paramType = (type.type as ParameterizedType).actualTypeArguments[0]
        val valueAdapter: TypeAdapter<Any?> = gson.getAdapter(TypeToken.get(paramType)) as TypeAdapter<Any?>

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<Maybe<Any?>>() {
            override fun write(out: JsonWriter, value: Maybe<Any?>?) {
                when (value) {
                    null, is Maybe.None -> out.nullValue()
                    is Maybe.Value -> valueAdapter.write(out, value.value)
                }
            }

            override fun read(`in`: JsonReader): Maybe<Any?> {
                return if (`in`.peek() == JsonToken.NULL) {
                    `in`.nextNull()
                    Maybe.Value(null)
                } else {
                    Maybe.Value(valueAdapter.read(`in`))
                }
            }
        } as TypeAdapter<T>
    }
}
