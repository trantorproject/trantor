package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.core.logging.getLogger
import dev.botta.trantor.data.jdbc.transactions.*
import dev.botta.trantor.tx.TransactionCallback

class ThreadLocalJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    private val logger = getLogger()
    private var threadLocalActiveTransaction: ThreadLocal<JdbcTransaction?> = ThreadLocal()
    private var threadLocalActiveTransactionCallbacks: ThreadLocal<MutableList<TransactionCallback>> =
        ThreadLocal.withInitial { mutableListOf() }

    override var activeTransaction: JdbcTransaction?
        get() = threadLocalActiveTransaction.get()
        set(value) {
            threadLocalActiveTransaction.set(value)
            threadLocalActiveTransactionCallbacks.set(mutableListOf())
        }

    override fun registerActiveTransactionCallback(callback: TransactionCallback) {
        if (!hasActiveTransaction()) return
        val callbacks = threadLocalActiveTransactionCallbacks.get()
        callbacks.add(callback)
        threadLocalActiveTransactionCallbacks.set(callbacks)
    }

    override fun onActiveTransactionClose(result: TransactionResults) {
        val callbacks = threadLocalActiveTransactionCallbacks.get()
        for (callback in callbacks) {
            try {
                when (result) {
                    TransactionResults.Commit -> callback.onCommit()
                    TransactionResults.Rollback -> callback.onRollback()
                }
            } catch (e: Exception) {
                logger.error("active transaction callback failed: ${e.message}", e)
            }
        }
        threadLocalActiveTransactionCallbacks.set(mutableListOf())
    }
}
