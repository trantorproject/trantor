package dev.botta.trantor.data.jdbc

import dev.botta.trantor.data.jdbc.connectionFactory.JdbcConnectionFactory
import java.io.PrintWriter
import java.sql.Connection

class FixedConnectionFactory(private val connection: Connection): JdbcConnectionFactory {
    override var loginTimeoutSecs = 0
    override var logWriter: PrintWriter? = null
    override val databaseDriver = "testDriver"

    override fun getConnection() = connection
}
