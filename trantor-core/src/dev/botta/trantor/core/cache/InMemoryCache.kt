package dev.botta.trantor.core.cache

import com.github.benmanes.caffeine.cache.Caffeine
import dev.botta.trantor.core.tx.TransactionManager
import kotlin.time.toJavaDuration

/**
 * An in-memory cache with two levels:
 *
 *  - **L1**, per transaction (a `ThreadLocal`): only visible inside the transaction that wrote it.
 *  - **L2**, global (Caffeine): shared by everyone.
 *
 * Splitting them is what keeps a rolled back change out of the shared cache. With no transaction open,
 * reads and writes go straight to L2. With one open, a write lands in L1 and only reaches L2 **after the
 * commit**, so a concurrent request never sees a value that was never saved. The transaction manager
 * guarantees the transaction is closed even on an exception, which is what clears L1.
 *
 * A read that misses L1 falls back to L2 and keeps what it found in L1.
 *
 * An [invalidate] inside a transaction drops the key from L2 straight away as well, so the transaction
 * that invalidated does not keep reading the old value through the fallback. A rollback therefore
 * costs one reload, which is a miss and never a wrong answer.
 *
 * Choosing between [put] and [invalidate] after a change is a trade-off between safety and cost:
 * `put` races with the `afterCommit` of other threads and can leave a stale value behind, while
 * `invalidate` is safe but lets a burst of requests through to the database.
 *
 * **Always cache snapshots, never mutable entities**: every caller gets the same object back.
 */
class InMemoryCache<K: Any, V: Any>(
    val settings: InMemoryCacheSettings = InMemoryCacheSettings(),
    val transactionManager: TransactionManager,
) {
    private val l1 = ThreadLocal<MutableMap<K, V>>.withInitial { mutableMapOf<K, V>() }
    private val l1ClearScheduled = ThreadLocal<Boolean>.withInitial { false }
    private val l2 = Caffeine.newBuilder()
        .expireAfterWrite(settings.expireAfter.toJavaDuration())
        .maximumSize(settings.maximumSize)
        .build<K, V>()

    fun get(key: K, loader: (K) -> V): V {
        tryGet(key)?.let { return it }
        if (transactionManager.activeTransaction == null) return l2.get(key, loader)
        return synchronized(key) {
            val value = loader(key)
            put(key, value)
            value
        }
    }

    private fun tryGetL1(key: K) = l1.get()[key]

    fun tryGet(key: K): V? {
        if (transactionManager.activeTransaction != null) {
            tryGetL1(key)?.let { return it }
            val value = l2.getIfPresent(key) ?: return null
            putL1(key, value)
            scheduleL1Clear()
            return value
        }
        return l2.getIfPresent(key)
    }

    private fun putL1(key: K, value: V) {
        l1.get()[key] = value
    }

    private fun scheduleL1Clear() {
        if (l1ClearScheduled.get()) return
        transactionManager.activeTransaction!!.afterComplete {
            l1.remove()
            l1ClearScheduled.remove()
        }
        l1ClearScheduled.set(true)
    }

    fun put(key: K, value: V) {
        if (transactionManager.activeTransaction != null) {
            putL1(key, value)
            scheduleL1Clear()
            transactionManager.activeTransaction!!.afterCommit {
                l2.put(key, value)
            }
            return
        }
        l2.put(key, value)
    }

    private fun invalidateL1(key: K) {
        l1.get().remove(key)
    }

    fun invalidate(key: K) {
        if (transactionManager.activeTransaction != null) {
            invalidateL1(key)
            // Now and not only after the commit: dropping the key from L1 alone means "no opinion", so the
            // next read inside this transaction falls back to L2 and gets the value this transaction just
            // replaced. Dropping an entry is never wrong, only a miss, so a rollback costs one reload
            l2.invalidate(key)
            // Still needed: another transaction can read the database and repopulate L2 with the old value
            // between here and the commit, since until then the change is not visible to it
            transactionManager.activeTransaction!!.afterCommit {
                l2.invalidate(key)
            }
            return
        }
        l2.invalidate(key)
    }

    fun invalidateIf(predicate: (V) -> Boolean) {
        if (transactionManager.activeTransaction != null) {
            l1.get().values.removeIf(predicate)
            l2.asMap().values.removeIf(predicate)
            transactionManager.activeTransaction!!.afterCommit {
                l2.asMap().values.removeIf(predicate)
            }
            return
        }
        l2.asMap().values.removeIf(predicate)
    }

    fun invalidateAll() {
        l1.remove()
        l2.invalidateAll()
    }
}
