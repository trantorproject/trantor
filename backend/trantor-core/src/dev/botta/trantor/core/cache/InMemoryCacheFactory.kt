package dev.botta.trantor.core.cache

import dev.botta.trantor.core.cache.caffeine.CaffeineInMemoryCache
import dev.botta.trantor.core.tx.TransactionManager

class InMemoryCacheFactory(private val transactionManager: TransactionManager) {
    fun <K: Any, V> create(settings: InMemoryCacheSettings): InMemoryCache<K, V> {
        return CaffeineInMemoryCache(transactionManager, settings)
    }
}
