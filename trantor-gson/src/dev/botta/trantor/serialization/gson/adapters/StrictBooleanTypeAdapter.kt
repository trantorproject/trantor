package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads a boolean from `true` or `false`, or from the strings "true" and "false" in any case. Gson reads any other
 * string as false, so "yes" was false with no error; here it fails, saying what came.
 */
class StrictBooleanTypeAdapter: TypeAdapter<Boolean?>() {
    override fun write(out: JsonWriter, value: Boolean?) {
        out.value(value)
    }

    override fun read(reader: JsonReader): Boolean? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        JsonToken.STRING -> {
            val text = reader.nextString()
            text.toBooleanStrictOrNull(ignoreCase = true)
                ?: throw JsonParseException("'$text' is not a boolean at ${reader.previousPath}")
        }
        else -> reader.nextBoolean()
    }

    private fun String.toBooleanStrictOrNull(ignoreCase: Boolean) = when {
        equals("true", ignoreCase) -> true
        equals("false", ignoreCase) -> false
        else -> null
    }
}
