package dev.botta.trantor.primitives.serialization

interface Serializer {
    fun serialize(obj: Any?): String
    fun <T> deserialize(serialized: String?, type: Class<T>): T
    fun <T> deserializeList(serialized: String?, type: Class<T>): List<T>
    fun <T> deserializeSet(serialized: String?, type: Class<T>): Set<T>
    fun <K, V> deserializeMap(serialized: String?, kType: Class<K>, vType: Class<V>): Map<K, V>
}

inline fun <reified T> Serializer.deserialize(serialized: String?) = deserialize(serialized, T::class.java)
inline fun <reified T> Serializer.deserializeList(serialized: String?) = deserializeList(serialized, T::class.java)
inline fun <reified T> Serializer.deserializeSet(serialized: String?) = deserializeSet(serialized, T::class.java)
inline fun <reified K, reified V> Serializer.deserializeMap(serialized: String?) = deserializeMap(serialized, K::class.java, V::class.java)
