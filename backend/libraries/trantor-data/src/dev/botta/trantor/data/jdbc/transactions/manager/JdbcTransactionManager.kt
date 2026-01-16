package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*
import dev.botta.trantor.tx.*
import java.sql.Connection

abstract class JdbcTransactionManager(private val dataSource: TransactionAwareDataSource): TransactionManager {
    protected abstract var activeTransaction: JdbcTransaction?

    init {
        dataSource.transactionManager = this
    }

    val activeConnection: Connection?
        get() = activeTransaction?.connection

    override fun beginTransaction(): Transaction {
        if (hasActiveTransaction()) {
            activeTransaction!!.beginNested()
        } else {
            activeTransaction = createTransaction()
        }
        return activeTransaction!!
    }

    override fun hasActiveTransaction() = activeTransaction != null

    private fun createTransaction(): JdbcTransaction {
        val connection = dataSource.connection ?: throw Exception("Could not connect to datasource. Check your connection settings")
        return JdbcTransaction(connection, ::onClose)
    }

    protected open fun onActiveTransactionClose(result: TransactionResults) {}

    private fun onClose(result: TransactionResults) {
        onActiveTransactionClose(result)
        val connection = activeConnection
        activeTransaction = null
        connection?.close()
    }
}
