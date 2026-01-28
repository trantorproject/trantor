package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.core.tx.*
import dev.botta.trantor.data.jdbc.transactions.*
import java.sql.Connection

abstract class JdbcTransactionManager(private val dataSource: TransactionAwareDataSource): TransactionManager {
    override val activeTransaction: Transaction?
        get() = jdbcActiveTransaction
    protected abstract var jdbcActiveTransaction: JdbcTransaction?

    init {
        dataSource.transactionManager = this
    }

    val activeConnection: Connection?
        get() = jdbcActiveTransaction?.connection

    override fun beginTransaction(): Transaction {
        if (hasActiveTransaction()) {
            jdbcActiveTransaction!!.beginNested()
        } else {
            jdbcActiveTransaction = createTransaction()
        }
        return jdbcActiveTransaction!!
    }

    fun hasActiveTransaction() = jdbcActiveTransaction != null

    private fun createTransaction(): JdbcTransaction {
        val connection = dataSource.connection ?: throw Exception("Could not connect to datasource. Check your connection settings")
        return JdbcTransaction(connection, ::onClose)
    }

    private fun onClose() {
        val connection = activeConnection
        jdbcActiveTransaction = null
        connection?.close()
    }
}
