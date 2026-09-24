@file:Suppress("ClassName")

package dev.botta.trantor.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AggregateTest {
    @Nested
    inner class `equality` {
        @Test
        fun `is by identity, not by state`() {
            val id = OrderId()
            val open = Order(id)
            val cancelled = Order(id).apply { cancel() }

            assertThat(open).isEqualTo(cancelled)
        }

        @Test
        fun `two aggregates with different ids are different`() {
            assertThat(Order(OrderId())).isNotEqualTo(Order(OrderId()))
        }

        @Test
        fun `an aggregate of another class is never the same, even with the same id`() {
            val id = OrderId()

            assertThat(Order(id)).isNotEqualTo(Invoice(id))
        }

        @Test
        fun `hashes by identity, so a set keeps one copy of an aggregate however it changed`() {
            val id = OrderId()

            val orders = setOf(Order(id), Order(id).apply { cancel() })

            assertThat(orders).hasSize(1)
        }
    }

    @Test
    fun `reads as its class and its id`() {
        val id = OrderId()

        assertThat(Order(id).toString()).isEqualTo("Order($id)")
    }

    private class OrderId: Id()

    private class Order(id: OrderId): Aggregate<OrderId>(id) {
        var isCancelled = false
            private set

        fun cancel() {
            isCancelled = true
        }
    }

    private class Invoice(id: OrderId): Aggregate<OrderId>(id)
}
