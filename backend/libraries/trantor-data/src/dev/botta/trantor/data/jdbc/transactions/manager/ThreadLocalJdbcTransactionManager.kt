package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.DataSource
import dev.botta.trantor.data.jdbc.transactions.JdbcTransaction

class ThreadLocalJdbcTransactionManager(dataSource: DataSource): JdbcTransactionManager(dataSource) {
    private var threadLocalActiveTransaction: ThreadLocal<JdbcTransaction?> = ThreadLocal()

    override var activeTransaction: JdbcTransaction?
        get() = threadLocalActiveTransaction.get()
        set(value) { threadLocalActiveTransaction.set(value) }
}
