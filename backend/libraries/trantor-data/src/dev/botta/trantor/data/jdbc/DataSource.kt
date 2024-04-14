package dev.botta.trantor.data.jdbc

import dev.botta.trantor.data.jdbc.connectionFactory.*
import dev.botta.trantor.data.jdbc.connectionFactory.credentials.JdbcCredentials
import dev.botta.trantor.data.jdbc.transactions.manager.*
import java.io.PrintWriter
import java.sql.*
import java.util.logging.Logger
import javax.sql.DataSource as JdbcDataSource

class DataSource(
    private val connectionFactory: JdbcConnectionFactory,
    private val credentials: JdbcCredentials,
    transactionManagerFactory: (DataSource) -> JdbcTransactionManager = { ThreadLocalJdbcTransactionManager(it) }
): JdbcDataSource {
    val transactionManager: JdbcTransactionManager = transactionManagerFactory(this)
    val databaseDriver = connectionFactory.databaseDriver

    constructor(credentials: JdbcCredentials): this(SimpleJdbcConnectionFactory(credentials), credentials)

    fun credentials(): JdbcCredentials {
        return credentials
    }

    fun acquire(): Connection {
        if (existsActiveTransaction()) return transactionManager.activeConnection!!
        return ManagedDataSourceConnection(
            this,
            connectionFactory.getConnection()
        )
    }

    fun release(connection: Connection) {
        if (connectionManagedByTransactionManager(connection)) return
        connection.close()
    }

    private fun connectionManagedByTransactionManager(connection: Connection) =
        transactionManager.activeConnection == connection

    private fun existsActiveTransaction() = transactionManager.hasActiveTransaction()

    override fun getLogWriter() = connectionFactory.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        connectionFactory.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        connectionFactory.loginTimeoutSecs = seconds
    }

    override fun getLoginTimeout() = connectionFactory.loginTimeoutSecs

    override fun getParentLogger(): Logger {
        throw SQLFeatureNotSupportedException()
    }

    override fun isWrapperFor(iface: Class<*>?) = false

    override fun <T: Any?> unwrap(iface: Class<T>?): T {
        throw SQLFeatureNotSupportedException()
    }

    override fun getConnection() = acquire()

    override fun getConnection(username: String?, password: String?): Connection {
        throw SQLFeatureNotSupportedException()
    }
}
