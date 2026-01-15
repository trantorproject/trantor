package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*
import dev.botta.trantor.tx.TransactionCallback

class SimpleJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    private var activeTransactionCallbacks: MutableList<TransactionCallback> = mutableListOf()

    override var activeTransaction: JdbcTransaction? = null
        set(value) {
            field = value
            activeTransactionCallbacks = mutableListOf()
        }

    override fun registerActiveTransactionCallback(callback: TransactionCallback) {
        if (!hasActiveTransaction()) return
        activeTransactionCallbacks.add(callback)
    }

    override fun onActiveTransactionClose(result: TransactionResults) {
        for (callback in activeTransactionCallbacks) {
            when (result) {
                TransactionResults.Commit -> callback.onCommit()
                TransactionResults.Rollback -> callback.onRollback()
            }
        }
        activeTransactionCallbacks = mutableListOf()
    }
}
