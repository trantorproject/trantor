package dev.botta.trantor.data.jdbc

import java.sql.DriverManager
import javax.sql.DataSource

class SimpleDataSource(private val settings: JdbcSettings) : DataSource {
    override fun getConnection() = DriverManager.getConnection(settings.url, settings.username, settings.password)

    override fun getConnection(username: String, password: String) = DriverManager.getConnection(settings.url, username, password)

    override fun getLogWriter() = throw UnsupportedOperationException()

    override fun setLogWriter(out: java.io.PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getLoginTimeout(): Int = throw UnsupportedOperationException()

    override fun getParentLogger() = throw UnsupportedOperationException()

    override fun <T> unwrap(iface: Class<T>): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
