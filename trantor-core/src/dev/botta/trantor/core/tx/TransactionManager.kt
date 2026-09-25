package dev.botta.trantor.core.tx

interface TransactionManager {
    val activeTransaction: Transaction?
    fun beginTransaction(): Transaction
}

inline fun <R> TransactionManager.transactional(runnable: (Transaction) -> R): R {
    val transaction = beginTransaction()
    // Rollback only on failure: when nested, the same transaction is still open after committing the savepoint,
    // and rolling it back would undo the outer transaction too
    val result = try {
        runnable(transaction)
    } catch (e: Throwable) {
        if (!transaction.isClosed) transaction.rollback()
        throw e
    }
    if (!transaction.isClosed) transaction.commit()
    return result
}
