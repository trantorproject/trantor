package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads an enum by the name of each value, or its [SerializedName] and alternates, as Gson does, but fails on one it
 * does not have, naming the ones it has. Gson reads it as null instead, which a class then reports as a field that
 * cannot be null, hiding why.
 */
class StrictEnumTypeAdapterFactory: TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        var raw = type.rawType
        if (!Enum::class.java.isAssignableFrom(raw) || raw == Enum::class.java) return null
        // A value with a body of its own is a subclass of the enum
        if (!raw.isEnum) raw = raw.superclass

        @Suppress("UNCHECKED_CAST")
        return Adapter(raw as Class<out Enum<*>>) as TypeAdapter<T>
    }

    private class Adapter(enumType: Class<out Enum<*>>): TypeAdapter<Enum<*>?>() {
        private val byName = linkedMapOf<String, Enum<*>>()
        private val nameOf = mutableMapOf<Enum<*>, String>()
        private val names: String

        init {
            for (constant in enumType.enumConstants) {
                val annotation = enumType.getField(constant.name).getAnnotation(SerializedName::class.java)
                val name = annotation?.value ?: constant.name
                nameOf[constant] = name
                byName[name] = constant
                annotation?.alternate?.forEach { byName.putIfAbsent(it, constant) }
            }
            names = nameOf.values.joinToString()
        }

        override fun write(out: JsonWriter, value: Enum<*>?) {
            out.value(value?.let { nameOf.getValue(it) })
        }

        override fun read(reader: JsonReader): Enum<*>? {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return null
            }

            val text = reader.nextString()
            return byName[text] ?: throw JsonParseException("'$text' is not one of $names at ${reader.previousPath}")
        }
    }
}
