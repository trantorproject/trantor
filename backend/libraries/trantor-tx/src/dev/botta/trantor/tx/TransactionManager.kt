package dev.botta.trantor.tx

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

interface TransactionManager {
    fun beginTransaction(): Transaction
    val transactionContext: CoroutineContext.Element
}

suspend inline fun <R> TransactionManager.transactional(crossinline runnable: suspend (Transaction) -> R) = withContext(this.transactionContext) {
    val transaction = beginTransaction()
    try {
        val result = runnable(transaction)
        if (!transaction.isClosed) transaction.commit()
        return@withContext result
    } catch (e: Throwable) {
        if (!transaction.isClosed) transaction.rollback()
        throw e
    }
}
