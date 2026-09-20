@file:Suppress("ClassName")

package dev.botta.trantor.core.cache

import dev.botta.trantor.core.tx.NullTransactionManager
import dev.botta.trantor.core.tx.Transaction
import dev.botta.trantor.core.tx.TransactionManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class InMemoryCacheTest {
    @Nested
    inner class `outside a transaction` {
        @Test
        fun `what was put is what comes back`() {
            val cache = cacheWith(NullTransactionManager())

            cache.put("order-7", "placed")

            assertThat(cache.tryGet("order-7")).isEqualTo("placed")
        }

        @Test
        fun `a key nobody cached is null, not an error`() {
            val cache = cacheWith(NullTransactionManager())

            assertThat(cache.tryGet("order-7")).isNull()
        }

        @Test
        fun `get loads what is missing and keeps it`() {
            val cache = cacheWith(NullTransactionManager())
            var loads = 0

            cache.get("order-7") { loads++; "placed" }
            cache.get("order-7") { loads++; "placed" }

            assertThat(loads).isEqualTo(1)
        }

        @Test
        fun `invalidate means the next read loads again`() {
            val cache = cacheWith(NullTransactionManager())
            cache.put("order-7", "placed")

            cache.invalidate("order-7")

            assertThat(cache.tryGet("order-7")).isNull()
        }

        @Test
        fun `invalidateIf drops only what matches`() {
            val cache = cacheWith(NullTransactionManager())
            cache.put("order-7", "placed")
            cache.put("order-8", "shipped")

            cache.invalidateIf { it == "placed" }

            assertThat(cache.tryGet("order-7")).isNull()
            assertThat(cache.tryGet("order-8")).isEqualTo("shipped")
        }

        @Test
        fun `invalidateAll leaves nothing`() {
            val cache = cacheWith(NullTransactionManager())
            cache.put("order-7", "placed")
            cache.put("order-8", "shipped")

            cache.invalidateAll()

            assertThat(cache.tryGet("order-7")).isNull()
            assertThat(cache.tryGet("order-8")).isNull()
        }
    }

    @Nested
    inner class `inside a transaction` {
        @Test
        fun `what was put is visible to the transaction that put it`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            transactions.begin()

            cache.put("order-7", "placed")

            assertThat(cache.tryGet("order-7")).isEqualTo("placed")
        }

        @Test
        fun `but it does not reach the shared cache until the commit`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            transactions.begin()
            cache.put("order-7", "placed")

            transactions.end()

            assertThat(cache.tryGet("order-7")).isNull()
        }

        @Test
        fun `a rollback leaves nothing behind, which is the whole point`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            transactions.begin()
            cache.put("order-7", "placed")

            transactions.rollback()

            assertThat(cache.tryGet("order-7")).isNull()
        }

        @Test
        fun `after the commit it is there for everyone`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            transactions.begin()
            cache.put("order-7", "placed")

            transactions.commit()

            assertThat(cache.tryGet("order-7")).isEqualTo("placed")
        }

        @Test
        fun `what the shared cache already had is readable`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            cache.put("order-7", "placed")

            transactions.begin()

            assertThat(cache.tryGet("order-7")).isEqualTo("placed")
        }

        @Test
        fun `invalidating reaches the shared cache on commit`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            cache.put("order-7", "placed")
            transactions.begin()

            cache.invalidate("order-7")
            transactions.commit()

            assertThat(cache.tryGet("order-7")).isNull()
        }

        @Test
        fun `and a rollback leaves the shared cache as it was`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            cache.put("order-7", "placed")
            transactions.begin()

            cache.invalidate("order-7")
            transactions.rollback()

            assertThat(cache.tryGet("order-7")).isEqualTo("placed")
        }

        @Test
        fun `get loads once for the whole transaction`() {
            val transactions = FakeTransactionManager()
            val cache = cacheWith(transactions)
            transactions.begin()
            var loads = 0

            cache.get("order-7") { loads++; "placed" }
            cache.get("order-7") { loads++; "placed" }

            assertThat(loads).isEqualTo(1)
        }
    }

    private fun cacheWith(transactions: TransactionManager) =
        InMemoryCache<String, String>(InMemoryCacheSettings(expireAfter = 5.minutes), transactions)

    /** A transaction manager that only does what the cache needs of it: hold one, and run the callbacks. */
    private class FakeTransactionManager: TransactionManager {
        override var activeTransaction: Transaction? = null

        fun begin() {
            activeTransaction = beginTransaction()
        }

        fun commit() {
            val transaction = activeTransaction as FakeTransaction
            activeTransaction = null
            transaction.commit()
            transaction.close()
        }

        fun rollback() {
            val transaction = activeTransaction as FakeTransaction
            activeTransaction = null
            transaction.rollback()
            transaction.close()
        }

        /** Ends without saying how, which is what an abandoned transaction looks like. */
        fun end() {
            val transaction = activeTransaction as FakeTransaction
            activeTransaction = null
            transaction.close()
        }

        override fun beginTransaction(): Transaction = FakeTransaction()
    }

    private class FakeTransaction: Transaction {
        override var isClosed = false

        private val onComplete = mutableListOf<() -> Unit>()
        private val onCommit = mutableListOf<() -> Unit>()
        private val onRollback = mutableListOf<() -> Unit>()

        override fun commit() {
            onCommit.forEach { it() }
        }

        override fun rollback() {
            onRollback.forEach { it() }
        }

        override fun afterComplete(action: () -> Unit) {
            onComplete.add(action)
        }

        override fun afterCommit(action: () -> Unit) {
            onCommit.add(action)
        }

        override fun afterRollback(action: () -> Unit) {
            onRollback.add(action)
        }

        override fun close() {
            isClosed = true
            onComplete.forEach { it() }
        }
    }
}
