@file:Suppress("ClassName")

package dev.botta.trantor.web.broadcast

import dev.botta.trantor.core.broadcast.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ChannelRegistryTest {
    @Nested
    inner class `a channel with no parameters` {
        @Test
        fun `matches its own path`() {
            registry.add(Orders())

            assertThat(registry.match("orders")?.channel).isInstanceOf(Orders::class.java)
        }

        @Test
        fun `matches nothing else`() {
            registry.add(Orders())

            assertThat(registry.match("invoices")).isNull()
        }

        @Test
        fun `has no parameters to hand over`() {
            registry.add(Orders())

            assertThat(registry.match("orders")?.params).isEmpty()
        }
    }

    @Nested
    inner class `a channel with parameters` {
        @Test
        fun `matches and says what filled them`() {
            registry.add(CustomerOrders())

            val match = registry.match("customers/7/orders")

            assertThat(match?.channel).isInstanceOf(CustomerOrders::class.java)
            assertThat(match?.params).containsEntry("customerId", "7")
        }

        @Test
        fun `each parameter gets its own name`() {
            registry.add(CustomerOrder())

            val match = registry.match("customers/7/orders/abc-1")

            assertThat(match?.params)
                .containsEntry("customerId", "7")
                .containsEntry("orderId", "abc-1")
        }

        @Test
        fun `a value may have letters, digits, dashes and underscores`() {
            registry.add(CustomerOrders())

            assertThat(registry.match("customers/a_b-1/orders")?.params)
                .containsEntry("customerId", "a_b-1")
        }

        @Test
        fun `a value cannot contain a slash, or it would cross into another segment`() {
            registry.add(CustomerOrders())

            assertThat(registry.match("customers/7/nested/orders")).isNull()
        }

        @Test
        fun `an empty value does not match`() {
            registry.add(CustomerOrders())

            assertThat(registry.match("customers//orders")).isNull()
        }
    }

    @Nested
    inner class `matching a path` {
        @Test
        fun `is the whole path, not a piece of it`() {
            registry.add(Orders())

            assertThat(registry.match("orders/7")).isNull()
            assertThat(registry.match("all-orders")).isNull()
        }

        @Test
        fun `with nothing registered matches nothing`() {
            assertThat(registry.match("orders")).isNull()
        }

        @Test
        fun `the first channel that matches wins`() {
            registry.add(CustomerOrders())
            registry.add(AnythingOrders())

            assertThat(registry.match("customers/7/orders")?.channel).isInstanceOf(CustomerOrders::class.java)
        }

        @Test
        fun `a channel registered twice still matches`() {
            registry.add(Orders())
            registry.add(Orders())

            assertThat(registry.match("orders")).isNotNull()
        }
    }

    private class Orders: Channel("orders")

    private class CustomerOrders: Channel("customers/{customerId}/orders")

    private class CustomerOrder: Channel("customers/{customerId}/orders/{orderId}")

    private class AnythingOrders: Channel("customers/{anything}/orders")

    private val registry = ChannelRegistry()
}
