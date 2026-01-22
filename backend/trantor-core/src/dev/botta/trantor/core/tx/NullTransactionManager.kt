package dev.botta.trantor.core.tx

class NullTransactionManager: TransactionManager {
    override fun beginTransaction(): Transaction {
        return NullTransaction()
    }

    override fun hasActiveTransaction(): Boolean {
        return false
    }

    override fun registerActiveTransactionCallback(callback: TransactionCallback) {
    }
}
