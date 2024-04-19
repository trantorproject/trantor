package dev.botta.trantor.data.jdbc

import dev.botta.trantor.data.jdbc.connectionFactory.JdbcConnectionFactory
import java.io.PrintWriter

class StubbedConnectionFactory(private val savePoints: SavePoints = SavePoints()): JdbcConnectionFactory {
    override var loginTimeoutSecs = 0
    override var logWriter: PrintWriter? = null
    override val databaseDriver = "testDriver"

    override fun getConnection() = ConnectionStub(savePoints)
}
