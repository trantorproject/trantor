@file:Suppress("ClassName")

package dev.botta.trantor.data.jdbc.transactions

import dev.botta.trantor.data.jdbc.ConnectionStub
import dev.botta.trantor.data.jdbc.SavePoints
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcTransactionTest {
    @Nested
    inner class `opening one` {
        @Test
        fun `takes the connection off auto commit, or nothing would be a transaction`() {
            transaction()

            assertThat(connection.autoCommit).isFalse()
        }

        @Test
        fun `it starts open`() {
            assertThat(transaction().isClosed).isFalse()
        }
    }

    @Nested
    inner class `committing` {
        @Test
        fun `commits the connection`() {
            transaction().commit()

            assertThat(connection.wasCommited).isTrue()
        }

        @Test
        fun `closes the transaction and gives the connection back`() {
            val transaction = transaction()

            transaction.commit()

            assertThat(transaction.isClosed).isTrue()
            assertThat(closes).isEqualTo(1)
        }

        @Test
        fun `puts auto commit back, because the connection is reused`() {
            transaction().commit()

            assertThat(connection.autoCommit).isTrue()
        }

        @Test
        fun `committing twice commits once`() {
            val transaction = transaction()

            transaction.commit()
            transaction.commit()

            assertThat(closes).isEqualTo(1)
        }

        @Test
        fun `runs what was waiting for the commit`() {
            val transaction = transaction()
            transaction.afterCommit { calls.add("after commit") }
            transaction.afterRollback { calls.add("after rollback") }

            transaction.commit()

            assertThat(calls).containsExactly("after commit")
        }

        @Test
        fun `runs them in the order they were added`() {
            val transaction = transaction()
            transaction.afterCommit { calls.add("first") }
            transaction.afterCommit { calls.add("second") }

            transaction.commit()

            assertThat(calls).containsExactly("first", "second")
        }
    }

    @Nested
    inner class `rolling back` {
        @Test
        fun `rolls back the connection and closes`() {
            val transaction = transaction()

            transaction.rollback()

            assertThat(connection.wasRollbacked).isTrue()
            assertThat(transaction.isClosed).isTrue()
        }

        @Test
        fun `runs what was waiting for a rollback, and nothing else`() {
            val transaction = transaction()
            transaction.afterCommit { calls.add("after commit") }
            transaction.afterRollback { calls.add("after rollback") }

            transaction.rollback()

            assertThat(calls).containsExactly("after rollback")
        }

        @Test
        fun `rolling back twice rolls back once`() {
            val transaction = transaction()

            transaction.rollback()
            transaction.rollback()

            assertThat(closes).isEqualTo(1)
        }
    }

    @Nested
    inner class `afterComplete` {
        @Test
        fun `runs on a commit`() {
            val transaction = transaction()
            transaction.afterComplete { calls.add("complete") }

            transaction.commit()

            assertThat(calls).containsExactly("complete")
        }

        @Test
        fun `and on a rollback`() {
            val transaction = transaction()
            transaction.afterComplete { calls.add("complete") }

            transaction.rollback()

            assertThat(calls).containsExactly("complete")
        }
    }

    @Nested
    inner class `a callback added too late` {
        @Test
        fun `says so instead of being dropped`() {
            val transaction = transaction()
            transaction.commit()

            assertThatThrownBy { transaction.afterCommit { } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("closed transaction")
        }
    }

    @Nested
    inner class `closing without saying how` {
        @Test
        fun `rolls back, because nobody said to keep the changes`() {
            transaction().close()

            assertThat(connection.wasRollbacked).isTrue()
        }

        @Test
        fun `after a commit it does nothing`() {
            val transaction = transaction()
            transaction.commit()

            transaction.close()

            assertThat(connection.wasRollbacked).isFalse()
        }
    }

    @Nested
    inner class `a nested transaction` {
        @Test
        fun `takes a savepoint instead of opening another one`() {
            transaction().beginNested()

            assertThat(savePoints.active).hasSize(1)
        }

        @Test
        fun `committing it only releases the savepoint, the outer one is still open`() {
            val transaction = transaction()
            transaction.beginNested()

            transaction.commit()

            assertThat(savePoints.released).hasSize(1)
            assertThat(connection.wasCommited).isFalse()
            assertThat(transaction.isClosed).isFalse()
        }

        @Test
        fun `rolling it back only undoes to the savepoint`() {
            val transaction = transaction()
            transaction.beginNested()

            transaction.rollback()

            assertThat(savePoints.rollbacked).hasSize(1)
            assertThat(transaction.isClosed).isFalse()
        }

        @Test
        fun `once it is done the outer one commits as usual`() {
            val transaction = transaction()
            transaction.beginNested()
            transaction.commit()

            transaction.commit()

            assertThat(connection.wasCommited).isTrue()
            assertThat(transaction.isClosed).isTrue()
        }

        @Test
        fun `they unwind one at a time, in the order they were opened`() {
            val transaction = transaction()
            transaction.beginNested()
            transaction.beginNested()

            transaction.commit()
            transaction.commit()

            assertThat(savePoints.released).hasSize(2)
            assertThat(connection.wasCommited).isFalse()
        }

        @Test
        fun `the callbacks of the outer one wait for the outer commit`() {
            val transaction = transaction()
            transaction.afterCommit { calls.add("after commit") }
            transaction.beginNested()

            transaction.commit()

            assertThat(calls).isEmpty()
        }
    }

    private fun transaction() = JdbcTransaction(connection) { closes++ }

    private val calls = mutableListOf<String>()
    private var closes = 0
    private val savePoints = SavePoints()
    private val connection = ConnectionStub(savePoints)
}
