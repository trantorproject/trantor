package dev.botta.trantor.data.jdbc

import dev.botta.trantor.data.jdbc.credentials.JdbcCredentials
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataSourceTest {
    @Test
    fun `acquire creates a new connection on each call`() {
        val connection1 = dataSource.acquire()

        val connection2 = dataSource.acquire()

        assertThat(connection1).isNotEqualTo(connection2)
    }

    @Test
    fun `release closes the connection`() {
        val connection = dataSource.acquire()

        dataSource.release(connection)

        assert(connection.isClosed)
    }

    @Test
    fun `acquire creates a new connection on each call when there is not an active transaction`() {
        val connection1 = dataSource.acquire()

        val connection2 = dataSource.acquire()

        assertThat(connection1).isNotEqualTo(connection2)
    }

    @Test
    fun `acquire returns the active transaction's connection on each call when there is an active transaction`() {
        val transactionManager = dataSource.transactionManager
        transactionManager.beginTransaction()
        val connection1 = dataSource.acquire()

        val connection2 = dataSource.acquire()

        assertThat(transactionManager.activeConnection).isEqualTo(connection1)
        assertThat(connection1).isEqualTo(connection2)
    }

    @Test
    fun `release should not close the active transaction connection`() {
        val transactionManager = dataSource.transactionManager
        transactionManager.beginTransaction()
        val connection = transactionManager.activeConnection!!

        dataSource.release(connection)

        assertThat(connection.isClosed).isEqualTo(false)
    }

    @Test
    fun `a connection acquired inside a transaction cannot be closed directly`() {
        val transactionManager = dataSource.transactionManager
        transactionManager.beginTransaction()
        val connection = dataSource.acquire()

        connection.close()

        assertThat(connection.isClosed).isEqualTo(false)
    }

    @Test
    fun `release close a connection distinct from the active transaction's one`() {
        val connection = dataSource.acquire()
        val transactionManager = dataSource.transactionManager
        transactionManager.beginTransaction()

        dataSource.release(connection)

        assertThat(connection.isClosed).isEqualTo(true)
    }

    private val fakeJdbcCredentials = mockk<JdbcCredentials>()
    private val dataSource = DataSource(StubbedConnectionFactory(), fakeJdbcCredentials)
}
