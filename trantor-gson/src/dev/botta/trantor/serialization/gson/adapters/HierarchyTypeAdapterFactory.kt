@file:Suppress("UNCHECKED_CAST")

package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.*
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.*
import java.io.IOException

// See: https://www.novatec-gmbh.de/en/blog/gson-object-hierarchies/
class HierarchyTypeAdapterFactory<T> private constructor(
    internal val baseType: Class<T>,
    internal val typeFieldName: String,
    private val maintainType: Boolean
): TypeAdapterFactory {
    private val labelToSubtype = linkedMapOf<String, Class<*>>()

    /** The subtypes by their label, in the order they were registered: what a schema of the base lists. */
    internal val subtypes: Map<String, Class<*>> get() = labelToSubtype.toMap()
    private val subtypeToLabel = mutableMapOf<Class<*>, String>()

    fun subtype(type: Class<out T>, label: String = type.simpleName) = apply {
        if (subtypeToLabel.containsKey(type) || labelToSubtype.containsKey(label)) {
            throw IllegalArgumentException("types and labels must be unique")
        }
        labelToSubtype[label] = type
        subtypeToLabel[type] = label
    }

    inline fun <reified S: T> subtype(label: String = S::class.java.simpleName) = subtype(S::class.java, label)

    override fun <R: Any> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (baseType.isAssignableFrom(type.rawType) && type.rawType != baseType) return createSubclass(gson, type)
        if (type.rawType != baseType) return null

        val labelToDelegate = mutableMapOf<String, TypeAdapter<*>>()
        val subtypeToDelegate = mutableMapOf<Class<*>, TypeAdapter<*>>()

        for (entry in labelToSubtype.entries) {
            val delegate = gson.getDelegateAdapter(this, TypeToken.get(entry.value))
            labelToDelegate[entry.key] = delegate
            subtypeToDelegate[entry.value] = delegate
        }

        return object: TypeAdapter<R>() {
            @Throws(IOException::class)
            override fun read(`in`: JsonReader): R {
                val jsonElement = Streams.parse(`in`)
                val labelJsonElement = if (maintainType) {
                    jsonElement.asJsonObject[typeFieldName]
                } else {
                    jsonElement.asJsonObject.remove(typeFieldName)
                }

                if (labelJsonElement == null) {
                    throw JsonParseException("cannot deserialize $baseType because it does not define a field named $typeFieldName")
                }

                val label = labelJsonElement.asString
                // registration requires that subtype extends T
                val delegate = labelToDelegate[label] as TypeAdapter<R>?
                    ?: throw JsonParseException(
                        "cannot deserialize $baseType subtype named $label, did you forget to register a subtype? " +
                            "The ones registered are ${labelToSubtype.keys.joinToString()}",
                    )
                return delegate.fromJsonTree(jsonElement)
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R) {
                throw Throwable("Invalid operation. This should not happen")
            }
        }.nullSafe()
    }

    private fun <R: Any> createSubclass(gson: Gson, subType: TypeToken<R>): TypeAdapter<R>? {
        val delegate = gson.getDelegateAdapter(this, subType)

        return object: TypeAdapter<R>() {
            @Throws(IOException::class)
            override fun read(`in`: JsonReader): R {
                return delegate.fromJsonTree(Streams.parse(`in`))
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R) {
                val srcType = value.javaClass
                val label = subtypeToLabel[srcType] ?:
                    throw JsonParseException("cannot serialize ${srcType.name}, did you forget to register a subtype?")
                val jsonObject = delegate.toJsonTree(value).asJsonObject

                if (maintainType) {
                    Streams.write(jsonObject, out)
                    return
                }

                val clone = JsonObject()
//                if (jsonObject.has(typeFieldName)) {
//                    throw JsonParseException("cannot serialize ${srcType.name} because it already defines a field named " + typeFieldName)
//                }

                clone.add(typeFieldName, JsonPrimitive(label))
                for (entry in jsonObject.entrySet()) {
                    clone.add(entry.key, entry.value)
                }
                Streams.write(clone, out)
            }
        }.nullSafe()
    }

    companion object {
        fun <T> of(baseType: Class<T>) = HierarchyTypeAdapterFactory(baseType, "type", false)

        inline fun <reified T> of() = of(T::class.java)

        fun <T> of(baseType: Class<T>, typeFieldName: String) = HierarchyTypeAdapterFactory(baseType, typeFieldName, false)

        inline fun <reified T> of(typeFieldName: String) = of(T::class.java, typeFieldName)

        fun <T> of(baseType: Class<T>, typeFieldName: String, maintainType: Boolean) =
            HierarchyTypeAdapterFactory(baseType, typeFieldName, maintainType)

        inline fun <reified T> of(typeFieldName: String, maintainType: Boolean) = of(T::class.java, typeFieldName, maintainType)
    }
}
