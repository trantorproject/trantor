package dev.botta.trantor.core.cache.caffeine

import com.github.benmanes.caffeine.cache.Caffeine
import dev.botta.trantor.core.cache.*
import dev.botta.trantor.core.tx.TransactionManager

class CaffeineInMemoryCache<K: Any, V>(
    private val transactionManager: TransactionManager,
    private val settings: InMemoryCacheSettings,
): InMemoryCache<K, V> {
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(settings.expirationAfterWrite)
        .maximumSize(settings.maximumSize)
        .build<K, V>()

    override fun getIfPresent(key: K): V? {
        return cache.getIfPresent(key)
    }

    override fun get(key: K, loader: (key: K) -> V): V {
        return cache.get(key, loader)
    }

    override fun <V2: V & Any> put(key: K, value: V2) {
        // TODO: Si guarda en cache afterCommit entonces los get van a estar mal
//        if (transactionManager.activeTransaction == null || !settings.writeAfterCommit) {
            doPut(key, value)
//            return
//        }
//        transactionManager.activeTransaction!!.afterCommit {
//            doPut(key, value)
//        }
    }

    private fun <V2: V & Any> doPut(key: K, value: V2) {
        cache.put(key, value)
    }

    override fun invalidate(key: K) {
        cache.invalidate(key)
    }

    override fun invalidateAll() {
        cache.invalidateAll()
    }
}
