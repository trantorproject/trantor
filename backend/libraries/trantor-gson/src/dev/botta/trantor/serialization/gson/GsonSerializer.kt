package dev.botta.trantor.serialization.gson

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import dev.botta.time.*
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.domain.*
import dev.botta.trantor.serialization.gson.adapters.*
import dev.botta.trantor.serialization.gson.adapters.kotlinReflective.KotlinReflectiveTypeAdapterFactory
import java.lang.reflect.Type
import java.time.*

class GsonSerializer: JsonSerializer {
    private val builder: GsonBuilder = GsonBuilder()
    private val yearMonthParser by lazy { YearMonthParser() }

    init {
        builder.registerTypeAdapterFactory(KotlinReflectiveTypeAdapterFactory.create())
        builder.registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeSerializer())
        builder.registerTypeAdapter(LocalDate::class.java, LocalDateSerializer())
        builder.registerTypeAdapter(LocalTime::class.java, LocalTimeSerializer())
        builder.registerTypeAdapter(YearMonth::class.java, StringValueSerializer(
            { yearMonthParser.parseISO8601(it) },
            { it.formatAsISO8601() }
        ))
        builder.registerTypeAdapter(Id::class.java, IdValueSerializer())
        builder.registerTypeAdapter(Money::class.java, StringValueSerializer({ Money(it) }, { it.plainString() }))
        builder.registerTypeAdapter(Email::class.java, StringValueSerializer({ Email(it) }))
    }

    fun registerTypeAdapter(type: Class<*>, adapter: Any) {
        builder.registerTypeAdapter(type, adapter)
    }

    inline fun <reified T> registerTypeAdapter(adapter: Any) {
        registerTypeAdapter(T::class.java, adapter)
    }

    fun registerTypeAdapterFactory(factory: TypeAdapterFactory) {
        builder.registerTypeAdapterFactory(factory)
    }

    override fun serialize(obj: Any?): String {
        return builder.create().toJson(obj)
    }

    override fun <T> deserialize(serialized: String?, type: Class<T>): T {
        return builder.create().fromJson(serialized, type)
    }

    fun <T> deserialize(serialized: String?, type: Type): T {
        return builder.create().fromJson(serialized, type)
    }

    inline fun <reified T> deserialize(serialized: String?): T {
        return deserialize(serialized, T::class.java)
    }

    override fun <T> deserializeList(serialized: String?): List<T> {
        val listType = object: TypeToken<ArrayList<T>>(){}.type
        return deserialize(serialized, listType)
    }

    override fun <T> deserializeSet(serialized: String?): Set<T> {
        return deserializeList<T>(serialized).toSet()
    }

    override fun <K, V> deserializeMap(serialized: String?): Map<K, V> {
        val mapType = object: TypeToken<Map<K, V>>(){}.type
        return deserialize(serialized, mapType)
    }
}
