package dev.botta.trantor.data.jdbc.transactions

import dev.botta.trantor.data.jdbc.transactions.manager.JdbcTransactionManager
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

class TransactionAwareDataSource(private val innerDataSource: DataSource): DataSource {
    var transactionManager: JdbcTransactionManager? = null

    override fun getLogWriter(): PrintWriter = innerDataSource.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        innerDataSource.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        innerDataSource.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = innerDataSource.loginTimeout

    override fun getParentLogger(): Logger = innerDataSource.parentLogger

    @Suppress("UNCHECKED_CAST")
    override fun <T: Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return this as T
        return innerDataSource.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this) || innerDataSource.isWrapperFor(iface)
    }

    override fun getConnection(): Connection {
        if (existsActiveTransaction()) return transactionManager?.activeConnection!!
        return ManagedDataSourceConnection(innerDataSource.connection, ::release)
    }

    private fun existsActiveTransaction() = transactionManager?.hasActiveTransaction() ?: false

    override fun getConnection(username: String?, password: String?): Connection {
        return innerDataSource.getConnection(username, password)
    }

    private fun release(connection: Connection) {
        if (connectionManagedByTransactionManager(connection)) return
        connection.close()
    }

    private fun connectionManagedByTransactionManager(connection: Connection) =
        transactionManager?.activeConnection == connection
}
