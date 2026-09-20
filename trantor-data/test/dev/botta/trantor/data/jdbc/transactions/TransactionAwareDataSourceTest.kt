@file:Suppress("ClassName")

package dev.botta.trantor.data.jdbc.transactions

import dev.botta.trantor.data.jdbc.ManagedDataSourceConnection
import dev.botta.trantor.data.jdbc.transactions.manager.SimpleJdbcTransactionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource

class TransactionAwareDataSourceTest {
    @Nested
    inner class `with no transaction open` {
        @Test
        fun `hands out a connection from the pool`() {
            assertThat(dataSource.connection).isNotNull()

            verify { inner.connection }
        }

        @Test
        fun `wraps it, so closing it can be intercepted`() {
            assertThat(dataSource.connection).isInstanceOf(ManagedDataSourceConnection::class.java)
        }

        @Test
        fun `closing it really closes it, since nobody else is using it`() {
            dataSource.connection!!.close()

            verify { pooled.close() }
        }

        @Test
        fun `a pool with nothing to give hands out nothing, rather than a wrapper around null`() {
            every { inner.connection } returns null

            assertThat(dataSource.connection).isNull()
        }
    }

    @Nested
    inner class `with a transaction open` {
        @Test
        fun `hands out the connection of the transaction, so the work is in it`() {
            val transactionConnection = openTransaction()

            assertThat(dataSource.connection).isSameAs(transactionConnection)
        }

        @Test
        fun `does not go to the pool again`() {
            openTransaction()

            dataSource.connection

            // Once, to open the transaction: asking again inside it hands back the same one
            verify(exactly = 1) { inner.connection }
        }

        @Test
        fun `it is wrapped too, so closing it cannot end the transaction`() {
            openTransaction()

            dataSource.connection!!.close()

            verify(exactly = 0) { pooled.close() }
        }

        @Test
        fun `which works because a wrapper answers equal to the connection it wraps`() {
            val transactionConnection = openTransaction()

            // Load bearing, and easy to lose: close() reports the connection underneath, while the guard
            // that decides whether to really close it holds the wrapper. Only this equals makes them meet
            assertThat(transactionConnection).isEqualTo(pooled)
        }
    }

    @Nested
    inner class `a connection taken before the transaction started` {
        @Test
        fun `is left alone when it turns out to be the one the transaction is using`() {
            val connection = dataSource.connection!!
            openTransaction()

            connection.close()

            verify(exactly = 0) { pooled.close() }
        }
    }

    @Nested
    inner class `credentials given by hand` {
        @Test
        fun `go straight to the pool, outside any transaction`() {
            val other = mockk<Connection>(relaxed = true)
            every { inner.getConnection("nico", "secret") } returns other

            assertThat(dataSource.getConnection("nico", "secret")).isSameAs(other)
        }
    }

    @Nested
    inner class `unwrapping` {
        @Test
        fun `finds this data source when that is what was asked for`() {
            assertThat(dataSource.unwrap(TransactionAwareDataSource::class.java)).isSameAs(dataSource)
            assertThat(dataSource.isWrapperFor(TransactionAwareDataSource::class.java)).isTrue()
        }

        @Test
        fun `asks the one underneath for a type only the driver knows`() {
            dataSource.unwrap(DriverSpecific::class.java)

            verify { inner.unwrap(DriverSpecific::class.java) }
        }
    }

    interface DriverSpecific

    /**
     * A real manager and not a mock: it takes its connection from this very data source, before there is a
     * transaction to speak of, so what it ends up holding is a [ManagedDataSourceConnection]. A mock handed
     * a raw connection sets up a state that never happens, and the tests above read false conclusions off it.
     */
    private fun openTransaction(): Connection {
        val manager = SimpleJdbcTransactionManager(dataSource)
        manager.beginTransaction()

        return manager.activeConnection!!
    }

    private val pooled = mockk<Connection>(relaxed = true)

    private val inner = mockk<DataSource>(relaxed = true).also {
        every { it.connection } returns pooled
    }

    private val dataSource = TransactionAwareDataSource(inner)
}
