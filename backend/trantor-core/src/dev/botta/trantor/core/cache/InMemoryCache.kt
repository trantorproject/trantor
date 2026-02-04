package dev.botta.trantor.core.cache

interface InMemoryCache<K: Any, V> {
    fun getIfPresent(key: K): V?
    fun get(key: K, loader: (key: K) -> V): V
    fun <V2: V & Any> put(key: K, value: V2)
    fun invalidate(key: K)
    fun invalidateAll()
}
