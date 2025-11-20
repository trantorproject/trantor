package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*
import kotlin.coroutines.CoroutineContext

class SimpleJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    private val element = TxElement()

    override var activeTransaction: JdbcTransaction?
        get() = element.tx
        set(value) { element.tx = value }

    override val transactionContext: CoroutineContext.Element
        get() = element

    class TxElement : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TxElement>
        override val key: CoroutineContext.Key<*> get() = Key

        var tx: JdbcTransaction? = null
    }
}
