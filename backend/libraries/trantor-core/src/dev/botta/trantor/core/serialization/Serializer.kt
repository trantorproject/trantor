package dev.botta.trantor.core.serialization

interface Serializer {
    fun serialize(obj: Any?): String
    fun <T> deserialize(serialized: String?, type: Class<T>): T
}

inline fun <reified T> Serializer.deserialize(serialized: String?) = deserialize(serialized, T::class.java)
