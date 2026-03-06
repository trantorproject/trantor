package dev.botta.trantor.core.cache

import com.github.benmanes.caffeine.cache.Caffeine
import dev.botta.trantor.core.tx.TransactionManager
import kotlin.time.toJavaDuration

/**
 * Cache in-memory con dos niveles:
 *  - L1: por transacción (ThreadLocal) → visible solo dentro de la tx actual
 *  - L2: global (Caffeine)          → compartido entre todas las transacciones
 *
 * Este cache se encarga de ver varios problemas sutiles
 * - Si no hay una tx activa usa directo el cache L2 (Caffeine)
 * - Si hay una tx activa usa un cache L1 con thread local, ese cache se limpia cuando se cierra la tx
 * - El transaction manager garantiza que si hay una excepcion las tx se cierran siempre y el callback se ejecuta
 * - Si algo no esta en el L1 se lee del L2 y se guarda en el L1
 * - Cuando usar put y cuando usar invalidate depende de seguridad vs performance.
 *   Con put puede haber race conditions de los afterCommit en threads y guardar un valor viejo.
 *   Con invalidate el problema de performance es que puede haber una rafaga de hits a db.
 *
 * IMPORTANTE: SIEMPRE GUARDAR SNAPSHOTS Y NUNCA ENTIDADES MUTABLES!!
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
            transactionManager.activeTransaction!!.afterCommit {
                l2.invalidate(key)
            }
            return
        }
        l2.invalidate(key)
    }

    fun invalidateAll() {
        l1.remove()
        l2.invalidateAll()
    }
}
