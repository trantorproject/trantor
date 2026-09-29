package dev.botta.trantor.serialization.gson.adapters.kotlinReflective

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType

/**
 * A value class of Kotlin (`@JvmInline value class Code(val value: String)`) is its value on the wire, as the JVM
 * already writes it inside another class: `{"code":"AR"}` and not `{"code":{"value":"AR"}}`.
 */
internal class ValueClassAdapter<T: Any>(gson: Gson, type: KClass<T>): TypeAdapter<T>() {
    private val constructor = type.primaryConstructor!!.apply { isAccessible = true }
    private val parameter = constructor.parameters.single()
    private val property = type.memberProperties.single { it.name == parameter.name }.apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val valueAdapter = gson.getAdapter(TypeToken.get(parameter.type.javaType)) as TypeAdapter<Any?>

    override fun write(out: JsonWriter, value: T?) {
        if (value == null) {
            out.nullValue()
            return
        }

        valueAdapter.write(out, property.get(value))
    }

    override fun read(reader: JsonReader): T? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }

        return constructor.call(valueAdapter.read(reader))
    }
}
