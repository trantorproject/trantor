package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

class ThreadLocalJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    private var threadLocalActiveTransaction: ThreadLocal<JdbcTransaction?> = ThreadLocal()

    override var activeTransaction: JdbcTransaction?
        get() = threadLocalActiveTransaction.get()
        set(value) { threadLocalActiveTransaction.set(value) }

    override val transactionContext: CoroutineContext.Element
        get() = PropagatingThreadLocalContext(threadLocalActiveTransaction)

    class PropagatingThreadLocalContext<T>(private val threadLocal: ThreadLocal<T>): ThreadContextElement<T> {
        companion object Key : CoroutineContext.Key<PropagatingThreadLocalContext<*>>

        override val key: CoroutineContext.Key<*>
            get() = Key

        private var coroutineValue: T = threadLocal.get()

        override fun updateThreadContext(context: CoroutineContext): T {
            val old = threadLocal.get()
            threadLocal.set(coroutineValue)
            return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: T) {
            coroutineValue = threadLocal.get()
            threadLocal.set(oldState)
        }
    }
}
