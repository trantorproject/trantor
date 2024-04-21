package dev.botta.trantor.core.serialization

interface Serializer {
    fun serialize(obj: Any?): String
    fun <T> deserialize(serialized: String?, type: Class<T>): T
    fun <T> deserializeList(serialized: String?): List<T>
    fun <T> deserializeSet(serialized: String?): Set<T>
    fun <K, V> deserializeMap(serialized: String?): Map<K, V>
}

inline fun <reified T> Serializer.deserialize(serialized: String?) = deserialize(serialized, T::class.java)
