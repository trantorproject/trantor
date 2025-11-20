package dev.botta.trantor.data.jdbc.transactions.cqbus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.tx.*

class TransactionalMiddleware(private val transactionManager: TransactionManager): Middleware {
    override suspend fun <T: Request<R>, R> execute(request: T, next: suspend (T) -> R, context: ExecutionContext): R {
        return transactionManager.transactional {
            next(request)
        }
    }
}
//
//// Sync ThreadLocal transaction with Coroutine transaction
//class TransactionContextElement(
//    private val tx: JdbcTransaction,
//    private val transactionManager: CoroutineJdbcTransactionManager
//) : ThreadContextElement<JdbcTransaction?> {
//
//    companion object Key : CoroutineContext.Key<TransactionContextElement>
//
//    override val key: CoroutineContext.Key<*> = Key
//
//    override fun updateThreadContext(context: CoroutineContext): JdbcTransaction? {
//        val old = transactionManager.threadLocalActiveTransaction.get()
//        transactionManager.threadLocalActiveTransaction.set(tx)
//        return old
//    }
//
//    override fun restoreThreadContext(context: CoroutineContext, oldState: JdbcTransaction?) {
//        transactionManager.threadLocalActiveTransaction.set(oldState)
//    }
//}
