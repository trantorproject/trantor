package dev.botta.trantor.core.tx

interface TransactionManager {
    val activeTransaction: Transaction?
    fun beginTransaction(): Transaction
}

inline fun <R> TransactionManager.transactional(runnable: (Transaction) -> R): R {
    val transaction = beginTransaction()
    try {
        val result = runnable(transaction)
        if (!transaction.isClosed) transaction.commit()
        return result
    } finally {
        if (!transaction.isClosed) transaction.rollback()
    }
}
