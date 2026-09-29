package dev.botta.trantor.serialization.gson.adapters.kotlinReflective

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.*
import java.lang.reflect.*
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

// From: https://github.com/livefront/gson-kotlin-adapter
/**
 * This [TypeAdapterFactory] constructs Kotlin classes using their default constructor, allowing
 * for properties to be initialized properly. This ensures that the JSON fulfills the nullability
 * contract of the model and calls the class's init block.
 */
class KotlinReflectiveTypeAdapterFactory private constructor() : TypeAdapterFactory {
    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType: Class<in T> = type.rawType
        if (rawType.isLocalClass) return null
        if (rawType.isInterface) return null
        if (rawType.isEnum) return null
        if (rawType.isAnnotationPresent(JsonAdapter::class.java)) return null
        if (!rawType.isAnnotationPresent(KOTLIN_METADATA)) return null
        val kotlinRawType: KClass<T> = type.toKClass()
        require(!kotlinRawType.isInner) { "Cannot serialize inner class ${rawType.name}" }
        // Its constructor has no Java constructor behind it, and on the wire it is its value
        if (kotlinRawType.isValue) return ValueClassAdapter(gson, kotlinRawType)

        val primaryConstructor: KFunction<T> = kotlinRawType.primaryConstructor
            ?.apply { isAccessible = true }
            ?: return null
        val declaringClass = primaryConstructor.javaConstructor!!.declaringClass
        val constructorAdapters = mutableMapOf<KParameter, TypeAdapter<*>>()
        val fieldAdapters = mutableMapOf<Field, TypeAdapter<*>>()
        val constructorMap: MutableMap<String, KParameter> = TreeMap(String.CASE_INSENSITIVE_ORDER)

        primaryConstructor.parameters.forEach { parameter: KParameter ->
            val names = parameter.getSerializedNames(declaringClass)
            if (names.isNotEmpty()) {
                // Retrieve adapters for serializable inner properties
                // The Java type of a value class is the one of its value, which the constructor does not take
                val valueClass = (parameter.type.classifier as? KClass<*>)?.takeIf { it.isValue }
                constructorAdapters[parameter] = if (valueClass != null) {
                    gson.getAdapter(valueClass.java)
                } else {
                    gson.getAdapter(type.resolveType(parameter.type.javaType))
                }
            }
            // Associate the parameter with the possible names
            names.forEach { name: String -> constructorMap[name] = parameter }
        }

        // Access fields not in constructor parameters
        kotlinRawType.memberProperties.mapNotNull { it.javaField }.filter { !constructorMap.containsKey(it.name) }
            .forEach { field ->
                fieldAdapters[field] = gson.getAdapter(type.resolveType(field.type))
            }

        return Adapter(
            gson.getDelegateAdapter(this, type),
            constructorAdapters,
            fieldAdapters,
            kotlinRawType,
            primaryConstructor,
            constructorMap
        )
    }

    internal class Adapter<T : Any>(
        private val delegateAdapter: TypeAdapter<T>,
        private val constructorAdapters: Map<KParameter, TypeAdapter<*>>,
        private val fieldAdapters: Map<Field, TypeAdapter<*>>,
        private val kClass: KClass<T>,
        private val primaryConstructor: KFunction<T>,
        private val constructorMap: Map<String, KParameter>
    ) : TypeAdapter<T>() {
        override fun write(writer: JsonWriter, value: T?) {
            if (value == null) {
                writer.nullValue()
                return
            }
            delegateAdapter.write(writer, value)
        }

        override fun read(reader: JsonReader): T? {
            require(!kClass.isAbstract) { "Cannot deserialize abstract class '${kClass.simpleName}'" }
            require(!kClass.isSealed) { "Cannot deserialize sealed class '${kClass.simpleName}'" }
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return null
            }
            val constructorParams: MutableMap<KParameter, Any?> = mutableMapOf()
            val nonConstructorFields: MutableMap<Field, Any?> = mutableMapOf()
            reader.beginObject()
            while (reader.hasNext()) {
                val propertyName = reader.nextName()
                if (constructorMap.containsKey(propertyName)) {
                    val parameter = constructorMap[propertyName]!!
                    val replacedValue: Any? = constructorParams.put(
                        parameter,
                        constructorAdapters.getValue(parameter).read(reader)
                    )
                    require(replacedValue == null) {
                        "${kClass.simpleName} declares multiple JSON fields named ${parameter.name}"
                    }
                } else {
                    val field = fieldAdapters.keys.firstOrNull { it.name == propertyName }
                    if (field != null) {
                        nonConstructorFields[field] = fieldAdapters.getValue(field).read(reader)
                    } else {
                        reader.skipValue()
                    }
                }
            }
            reader.endObject()

            // A null for a parameter that cannot be null but has a default is as if it were left out: the strict mode
            // of OpenAI sends every field, and null for the ones the model has nothing to say about
            constructorParams.entries.removeIf { (param, value) ->
                value == null && param.isOptional && !param.type.isMarkedNullable
            }

            // Set to null missing non-optional parameters (nullables get null value and non-nullables throws JsonParseException next)
            primaryConstructor.parameters
                .filter { !it.isOptional }
                .forEach { constructorParams.putIfAbsent(it, null) }

            constructorParams.forEach { (param, value) ->
                if (value == null && !param.type.isMarkedNullable) {
                    throw JsonParseException("${param.name} cannot be null in type '${kClass.simpleName}'")
                }
                // Gson knows nothing of the nullability of what a list or a map holds, which Kotlin says in the type
                nullInside(value, param.type, param.name.orEmpty())?.let {
                    throw JsonParseException("$it cannot be null in type '${kClass.simpleName}'")
                }
            }
            try {
                val instance = primaryConstructor.callBy(constructorParams)
                // Set fields not present in constructor
                nonConstructorFields.forEach { (field, value) ->
                    if (!field.canAccess(instance)) field.trySetAccessible()
                    field.set(instance, value)
                }
                return instance
            } catch(e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    companion object {
        /**
         * Where a null is inside [value], a list or a map whose [type] says it cannot hold one, like `tags[1]` or
         * `scores[a]`; null when there is none. It goes down into lists of lists and maps of lists.
         */
        internal fun nullInside(value: Any?, type: KType, path: String): String? {
            val arguments = type.arguments.mapNotNull { it.type }

            return when (value) {
                is Collection<*> -> {
                    val itemType = arguments.singleOrNull() ?: return null
                    value.withIndex().firstNotNullOfOrNull { (i, item) ->
                        if (item == null && !itemType.isMarkedNullable) "$path[$i]"
                        else nullInside(item, itemType, "$path[$i]")
                    }
                }
                is Map<*, *> -> {
                    val valueType = arguments.getOrNull(1) ?: return null
                    value.entries.firstNotNullOfOrNull { (key, item) ->
                        if (item == null && !valueType.isMarkedNullable) "$path[$key]"
                        else nullInside(item, valueType, "$path[$key]")
                    }
                }
                else -> null
            }
        }

        /**
         * Classes annotated with this are eligible for this adapter.
         */
        private val KOTLIN_METADATA: Class<Metadata> = Metadata::class.java

        /**
         * Returns an new instance of [KotlinReflectiveTypeAdapterFactory] which constructs classes
         * using the default constructor, allowing for properties to initialized the properly.
         * This ensures that the JSON fulfills the nullability contract of the model and calls the
         * class's init block.
         */
        fun create() = KotlinReflectiveTypeAdapterFactory()
    }
}
