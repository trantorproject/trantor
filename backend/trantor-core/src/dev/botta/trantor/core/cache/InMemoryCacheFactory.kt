package dev.botta.trantor.core.cache

interface InMemoryCacheFactory {
    fun <K: Any, V: Any> create(settings: InMemoryCacheSettings = InMemoryCacheSettings()): InMemoryCache<K, V>
}
