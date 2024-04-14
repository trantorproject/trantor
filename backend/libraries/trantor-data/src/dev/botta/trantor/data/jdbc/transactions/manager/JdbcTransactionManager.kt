package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.DataSource
import dev.botta.trantor.data.jdbc.transactions.JdbcTransaction
import dev.botta.trantor.tx.*
import java.sql.Connection

abstract class JdbcTransactionManager(private val dataSource: DataSource): TransactionManager {
    protected abstract var activeTransaction: JdbcTransaction?

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

    private fun createTransaction(): JdbcTransaction {
        return JdbcTransaction(dataSource.acquire(), ::onClose)
    }

    fun hasActiveTransaction() = activeTransaction != null

    private fun onClose() {
        val connection = activeConnection!!
        endTransaction()
        dataSource.release(connection)
    }

    private fun endTransaction() {
        activeTransaction = null
    }
}
