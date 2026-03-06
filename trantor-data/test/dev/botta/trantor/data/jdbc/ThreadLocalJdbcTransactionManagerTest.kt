package dev.botta.trantor.data.jdbc

import dev.botta.trantor.core.tx.Transaction
import dev.botta.trantor.core.tx.transactional
import dev.botta.trantor.data.jdbc.transactions.TransactionAwareDataSource
import dev.botta.trantor.data.jdbc.transactions.manager.ThreadLocalJdbcTransactionManager
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import javax.sql.DataSource

class ThreadLocalJdbcTransactionManagerTest {
    @Test
    fun `there's no active connection if there's no active transaction`() {
        assertThat(transactionManager.activeConnection).isNull()
    }

    @Test
    fun `there's an active connection if there's an active transaction`() {
        transactionManager.beginTransaction()

        assertThat(transactionManager.activeConnection).isNotNull
    }

    @Test
    fun `when a transaction is committed the connection is closed`() {
        val transaction = transactionManager.beginTransaction()
        val connection = transactionManager.activeConnection

        transaction.commit()

        assertThat(transactionManager.activeConnection).isNull()
        assert(connection!!.isClosed)
    }

    @Test
    fun `when a transaction is rollbacked the connection is closed`() {
        val transaction = transactionManager.beginTransaction()
        val connection = transactionManager.activeConnection

        transaction.rollback()

        assertThat(transactionManager.activeConnection).isNull()
        assert(connection!!.isClosed)
    }

    @Test
    fun `autocommit is disabled when a transaction is started`() {
        transactionManager.beginTransaction()

        assertThat(transactionManager.activeConnection?.autoCommit).isFalse
    }

    @Test
    fun `when a transaction is committed autocommit is enabled`() {
        val transaction = transactionManager.beginTransaction()
        val connection = transactionManager.activeConnection

        transaction.commit()

        assertThat(connection?.autoCommit).isTrue
    }

    @Test
    fun `commit an already committed transaction does nothing`() {
        val transaction = transactionManager.beginTransaction()
        transaction.commit()

        assertDoesNotThrow {
            transaction.commit()
        }
    }

    @Test
    fun `rollback an already committed transaction does nothing`() {
        val transaction = transactionManager.beginTransaction()
        transaction.commit()

        assertDoesNotThrow {
            transaction.rollback()
        }
    }

    @Test
    fun `when a transaction is rollbacked autocommit is enabled`() {
        val transaction = transactionManager.beginTransaction()
        val connection = transactionManager.activeConnection

        transaction.rollback()

        assertThat(connection?.autoCommit).isTrue
    }

    @Test
    fun `there's an active connection only inside a transactional scope`() {
        transactionManager.transactional {
            assertThat(transactionManager.activeConnection).isNotNull
        }
        assertThat(transactionManager.activeConnection).isNull()
    }

    @Test
    fun `the connection is committed when it leaves the transactional scope`() {
        val connection = ConnectionStub(savePoints)
        every { dataSource.connection } returns connection

        transactionManager.transactional { }

        assert(connection.wasCommited)
    }

    @Test
    fun `the connection is rollbacked when an exception occurs inside the transactional scope`() {
        val connection = ConnectionStub(savePoints)
        every { dataSource.connection } returns connection

        assertThrows<Exception> {
            transactionManager.transactional { throw Exception("Some error") }
        }
        assert(connection.wasRollbacked)
    }

    @Test
    fun `a transaction can be commited inside a transactional scope`() {
        val connection = ConnectionStub(savePoints)
        every { dataSource.connection } returns connection

        transactionManager.transactional { transaction ->
            transaction.commit()
        }

        assert(connection.wasCommited)
    }

    @Test
    fun `a transaction can be rollbacked inside a transactional scope`() {
        val connection = ConnectionStub(savePoints)
        every { dataSource.connection } returns connection

        transactionManager.transactional { transaction ->
            transaction.rollback()
        }

        assert(connection.wasRollbacked)
    }

    @Test
    fun `can start a transaction simultaneously per thread`() {
        val transaction1 = transactionManager.beginTransaction()
        var transaction2: Transaction? = null
        val otherThread = createThread {
            transaction2 = transactionManager.beginTransaction()
        }

        startAndWait(otherThread)

        assertThat(transaction1).isNotEqualTo(transaction2)
    }

    @Test
    fun `nested begin a transaction doesn't start another transaction`() {
        val transaction1 = transactionManager.beginTransaction()

        val transaction2 = transactionManager.beginTransaction()

        assertThat(transaction1).isEqualTo(transaction2)
    }

    @Test
    fun `nested begin a transaction sets a savepoint`() {
        transactionManager.beginTransaction()

        transactionManager.beginTransaction()

        assertThat(savePoints.active.size).isEqualTo(1)
    }

    @Test
    fun `nested transaction commit releases the last savepoint`() {
        transactionManager.beginTransaction()
        transactionManager.beginTransaction()
        val transaction = transactionManager.beginTransaction()
        val lastSavepoint = savePoints.active.last()

        transaction.commit()

        assertThat(savePoints.active.size).isEqualTo(1)
        assertThat(savePoints.active.contains(lastSavepoint)).isFalse
        assertThat(savePoints.released.contains(lastSavepoint)).isTrue
    }

    @Test
    fun `nested transaction rollback rollbacks the last savepoint`() {
        transactionManager.beginTransaction()
        transactionManager.beginTransaction()
        val transaction = transactionManager.beginTransaction()
        val lastSavepoint = savePoints.active.last()

        transaction.rollback()

        assertThat(savePoints.active.size).isEqualTo(1)
        assertThat(savePoints.active.contains(lastSavepoint)).isFalse
        assertThat(savePoints.rollbacked.contains(lastSavepoint)).isTrue
    }

    @Test
    fun `nested begin a transaction in a transactional scope doesn't start another transaction`() {
        transactionManager.transactional { transaction1 ->

            val transaction2 = transactionManager.beginTransaction()

            assertThat(transaction1).isEqualTo(transaction2)
        }
    }

    @BeforeEach
    fun beforeEach() {
        val connection = ConnectionStub(savePoints)
        every { dataSource.connection } returns connection
    }

    private fun createThread(name: String = "OtherThread", runnable: Runnable): Thread {
        return Thread(runnable, name)
    }

    private fun startAndWait(otherThread: Thread) {
        otherThread.start()
        otherThread.join()
    }

    private var savePoints = SavePoints()
    private val dataSource = mockk<DataSource>()
    private val transactionManager = ThreadLocalJdbcTransactionManager(TransactionAwareDataSource(dataSource))
}
