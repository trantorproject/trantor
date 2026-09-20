@file:Suppress("ClassName")

package dev.botta.trantor.primitives.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EventHandlerTest {
    @Nested
    inner class `handlerType` {
        @Test
        fun `is the class name when nothing says otherwise`() {
            assertThat(SendReceipt().handlerType).isEqualTo("SendReceipt")
        }

        @Test
        fun `is what the annotation says, so a class can be renamed`() {
            assertThat(NotifyWarehouse().handlerType).isEqualTo("warehouse.notify.v2")
        }

        @Test
        fun `an anonymous class has no name to store, and says so`() {
            assertThatThrownBy { (object {})::class.handlerType() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Use @EventHandlerType")
        }
    }

    @Nested
    inner class `defaults` {
        @Test
        fun `a handler runs after the commit, so it never sees a rolled back change`() {
            assertThat(SendReceipt().afterCommit).isTrue()
        }

        @Test
        fun `a handler runs in line unless it says otherwise`() {
            assertThat(SendReceipt().queued).isNull()
        }
    }

    @Nested
    inner class `a queued handler` {
        @Test
        fun `goes to the default queue with no delay`() {
            assertThat(RebuildReport().queued).isEqualTo(QueuedEventConfig(null, 0))
        }

        @Test
        fun `can name its queue and ask to wait`() {
            assertThat(SendDigest().queued).isEqualTo(QueuedEventConfig("digests", 60))
        }
    }

    private class OrderPlaced: Event()

    private class SendReceipt: EventHandler {
        override val eventTypes = listOf(OrderPlaced::class)

        override fun on(event: Event) {}
    }

    @EventHandlerType("warehouse.notify.v2")
    private class NotifyWarehouse: EventHandler {
        override val eventTypes = listOf(OrderPlaced::class)

        override fun on(event: Event) {}
    }

    private class RebuildReport: QueuedEventHandler() {
        override val eventTypes = listOf(OrderPlaced::class)

        override fun on(event: Event) {}
    }

    private class SendDigest: QueuedEventHandler("digests", 60) {
        override val eventTypes = listOf(OrderPlaced::class)

        override fun on(event: Event) {}
    }
}
