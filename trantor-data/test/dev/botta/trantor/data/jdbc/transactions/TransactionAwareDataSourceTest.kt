@file:Suppress("ClassName")

package dev.botta.trantor.data.jdbc.transactions

import dev.botta.trantor.data.jdbc.ManagedDataSourceConnection
import dev.botta.trantor.data.jdbc.transactions.manager.JdbcTransactionManager
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
            openTransactionOn(pooled)

            assertThat(dataSource.connection).isSameAs(pooled)
        }

        @Test
        fun `does not go to the pool at all`() {
            openTransactionOn(pooled)

            dataSource.connection

            verify(exactly = 0) { inner.connection }
        }

        @Test
        fun `hands it over raw, so closing it is the caller's business`() {
            openTransactionOn(pooled)

            assertThat(dataSource.connection).isNotInstanceOf(ManagedDataSourceConnection::class.java)
        }
    }

    @Nested
    inner class `a connection taken before the transaction started` {
        @Test
        fun `is left alone when it turns out to be the one the transaction is using`() {
            val connection = dataSource.connection!!
            openTransactionOn(pooled)

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

    private fun openTransactionOn(connection: Connection) {
        val manager = mockk<JdbcTransactionManager>(relaxed = true)
        every { manager.hasActiveTransaction() } returns true
        every { manager.activeConnection } returns connection

        dataSource.transactionManager = manager
    }

    private val pooled = mockk<Connection>(relaxed = true)

    private val inner = mockk<DataSource>(relaxed = true).also {
        every { it.connection } returns pooled
    }

    private val dataSource = TransactionAwareDataSource(inner)
}
