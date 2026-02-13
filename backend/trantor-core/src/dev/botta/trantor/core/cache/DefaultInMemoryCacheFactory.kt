package dev.botta.trantor.core.cache

import dev.botta.trantor.core.tx.TransactionManager

class DefaultInMemoryCacheFactory(private val transactionManager: TransactionManager): InMemoryCacheFactory {
    override fun <K: Any, V: Any> create(settings: InMemoryCacheSettings): InMemoryCache<K, V> {
        return InMemoryCache(settings, transactionManager)
    }
}
