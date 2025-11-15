package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*

class CoroutineJdbcTransactionManager(
    dataSource: TransactionAwareDataSource
) : JdbcTransactionManager(dataSource) {
    val threadLocalActiveTransaction = ThreadLocal<JdbcTransaction?>()

    override var activeTransaction: JdbcTransaction?
        get() = threadLocalActiveTransaction.get()
        set(value) = threadLocalActiveTransaction.set(value)
}
