package dev.botta.trantor.serialization.gson

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import dev.botta.time.*
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSchemaSource
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.domain.*
import dev.botta.trantor.serialization.gson.adapters.*
import dev.botta.trantor.serialization.gson.adapters.kotlinReflective.KotlinReflectiveTypeAdapterFactory
import java.lang.reflect.Type
import kotlin.reflect.KType
import java.time.*

/**
 * The [JsonSerializer] of Trantor, on Gson, with the adapters that make it read Kotlin classes as Kotlin reads them
 * (through the primary constructor, with its defaults and its nullability), the types of the domain, and the strict
 * reading of booleans and enums.
 *
 * The [Gson] is built once and kept, since building it throws away the adapters it worked out for each type, which
 * made every call several times slower. Registering an adapter builds it again on the next call.
 */
class GsonSerializer: JsonSerializer, JsonSchemaSource {
    private val builder: GsonBuilder = GsonBuilder()
    private val yearMonthParser by lazy { YearMonthParser() }

    @Volatile
    private var gson: Gson? = null

    /** The types registered with an adapter, with the schema of what it reads, or null when it gave none. */
    private val schemas = mutableMapOf<Class<*>, JsonObject?>()
    private val hierarchies = mutableListOf<HierarchyTypeAdapterFactory<*>>()

    init {
        builder.registerTypeAdapterFactory(KotlinReflectiveTypeAdapterFactory.create())
        builder.registerTypeAdapterFactory(MaybeTypeAdapterFactory())
        builder.registerTypeAdapterFactory(IdTypeAdapterFactory())
        builder.registerTypeAdapterFactory(StrictEnumTypeAdapterFactory())
        builder.registerTypeAdapter(Boolean::class.javaPrimitiveType, StrictBooleanTypeAdapter())
        builder.registerTypeAdapter(Boolean::class.javaObjectType, StrictBooleanTypeAdapter())
        // With an offset, which LocalDateTimeParser requires, as date-time does
        registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeSerializer(), string("format" to "date-time"))
        registerTypeAdapter(LocalDate::class.java, LocalDateSerializer(), string("format" to "date"))
        // Without an offset, which the time format of JSON Schema requires, so it goes as a pattern
        registerTypeAdapter(LocalTime::class.java, LocalTimeSerializer(), string("pattern" to TIME_PATTERN))
        registerTypeAdapter(YearMonth::class.java, StringValueSerializer(
            { yearMonthParser.parseISO8601(it) },
            { it.formatAsISO8601() },
            string("pattern" to YEAR_MONTH_PATTERN),
        ))
        registerTypeAdapter(
            Money::class.java,
            StringValueSerializer({ Money(it) }, { it.plainString() }, string("pattern" to MONEY_PATTERN)),
        )
        registerTypeAdapter(Email::class.java, StringValueSerializer({ Email(it) }, schema = string("format" to EMAIL)))
        builder.setExclusionStrategies(DelegatedPropertiesExclusion())
    }

    /** The Gson this serializer uses, with every adapter registered so far. */
    fun getGson(): Gson = gson ?: synchronized(this) { gson ?: builder.create().also { gson = it } }

    /**
     * Reads and writes [type] with [adapter]. The [schema] says what it reads, for [schemaOf]; a
     * [StringValueSerializer] says it on its own. Without one, a schema of a type that holds it fails.
     */
    fun registerTypeAdapter(type: Class<*>, adapter: Any, schema: JsonObject? = null) {
        synchronized(this) {
            builder.registerTypeAdapter(type, adapter)
            schemas[type] = schema ?: (adapter as? StringValueSerializer<*>)?.schema
            gson = null
        }
    }

    inline fun <reified T> registerTypeAdapter(adapter: Any, schema: JsonObject? = null) {
        registerTypeAdapter(T::class.java, adapter, schema)
    }

    /** What an adapter registered for [type] reads, for one registered without saying it, or by someone else. */
    fun registerSchema(type: Class<*>, schema: JsonObject) {
        synchronized(this) { schemas[type] = schema }
    }

    /**
     * Reads and writes the types [factory] makes adapters for. A [HierarchyTypeAdapterFactory] says what it reads on
     * its own; the types of any other have to be given a schema with [registerSchema] for [schemaOf].
     */
    fun registerTypeAdapterFactory(factory: TypeAdapterFactory) {
        synchronized(this) {
            builder.registerTypeAdapterFactory(factory)
            if (factory is HierarchyTypeAdapterFactory<*>) hierarchies += factory
            gson = null
        }
    }

    /** The JSON Schema of what this serializer reads as [type], by the rules it reads by: see docs/trantor-gson.md. */
    override fun schemaOf(type: KType): JsonObject {
        val (schemas, hierarchies) = synchronized(this) { schemas.toMap() to hierarchies.toList() }
        return GsonJsonSchemas(getGson(), schemas, hierarchies).of(type)
    }

    override fun serialize(obj: Any?): String {
        return getGson().toJson(obj)
    }

    override fun <T> deserialize(serialized: String?, type: Class<T>): T {
        return getGson().fromJson(serialized, type)
    }

    fun <T> deserialize(serialized: String?, type: Type): T {
        return getGson().fromJson(serialized, type)
    }

    inline fun <reified T> deserialize(serialized: String?): T {
        return deserialize(serialized, T::class.java)
    }

    override fun <T> deserializeList(serialized: String?, type: Class<T>): List<T> {
        val listType = TypeToken.getParameterized(ArrayList::class.java, boxed(type)).type
        return deserialize(serialized, listType)
    }

    override fun <T> deserializeSet(serialized: String?, type: Class<T>): Set<T> {
        return deserializeList(serialized, type).toSet()
    }

    override fun <K, V> deserializeMap(serialized: String?, kType: Class<K>, vType: Class<V>): Map<K, V> {
        val mapType = TypeToken.getParameterized(Map::class.java, boxed(kType), boxed(vType)).type
        return deserialize(serialized, mapType)
    }

    /**
     * A generic type argument cannot be a primitive, and `Int::class.java` is `int`, so `deserializeList<Int>`
     * and `deserializeMap<String, Int>` would fail on the type they most obviously want.
     */
    private fun boxed(type: Class<*>): Class<*> = wrappers[type] ?: type

    private fun string(vararg rest: Pair<String, Any?>) = Json.obj("type" to "string", *rest)

    companion object {
        private const val TIME_PATTERN = "^\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$"
        private const val EMAIL = "email"
        private const val YEAR_MONTH_PATTERN = "^\\d{4}-\\d{2}$"

        /**
         * An amount with a dot for the decimals, as Money reads it. Without it a model asked in Spanish may write
         * 1234,50, which Money cannot read, and an answer that is an object gets no second try (plan-gson, P4).
         */
        private const val MONEY_PATTERN = "^-?\\d+(\\.\\d+)?$"

        private val wrappers = mapOf<Class<*>, Class<*>>(
            java.lang.Boolean.TYPE to java.lang.Boolean::class.java,
            java.lang.Byte.TYPE to java.lang.Byte::class.java,
            Character.TYPE to Character::class.java,
            java.lang.Short.TYPE to java.lang.Short::class.java,
            Integer.TYPE to Integer::class.java,
            java.lang.Long.TYPE to java.lang.Long::class.java,
            java.lang.Float.TYPE to java.lang.Float::class.java,
            java.lang.Double.TYPE to java.lang.Double::class.java,
        )
    }
}
