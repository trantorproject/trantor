package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*

class ThreadLocalJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    private var threadLocalActiveTransaction: ThreadLocal<JdbcTransaction?> = ThreadLocal()

    override var activeTransaction: JdbcTransaction?
        get() = threadLocalActiveTransaction.get()
        set(value) { threadLocalActiveTransaction.set(value) }
}
