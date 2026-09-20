@file:Suppress("ClassName")

package dev.botta.trantor.primitives.events

import dev.botta.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

class EventTest {
    @Nested
    inner class `identity` {
        @Test
        fun `gets one of its own`() {
            assertThat(OrderPlaced().id).isNotNull()
        }

        @Test
        fun `two events are never the same one`() {
            assertThat(OrderPlaced().id).isNotEqualTo(OrderPlaced().id)
        }

        @Test
        fun `an id can be given, so a redelivery is the same event`() {
            val id = UUID.randomUUID()

            assertThat(OrderPlaced(id).id).isEqualTo(id)
        }
    }

    @Nested
    inner class `equality` {
        @Test
        fun `is the id, not the contents`() {
            val id = UUID.randomUUID()

            assertThat(OrderPlaced(id)).isEqualTo(OrderPlaced(id))
            assertThat(OrderPlaced(id).hashCode()).isEqualTo(OrderPlaced(id).hashCode())
        }

        @Test
        fun `two events with different ids are different`() {
            assertThat(OrderPlaced()).isNotEqualTo(OrderPlaced())
        }

        @Test
        fun `the same id in another class is another event`() {
            val id = UUID.randomUUID()

            assertThat(OrderPlaced(id)).isNotEqualTo(OrderCancelled(id))
        }

        @Test
        fun `is not equal to something that is not an event`() {
            assertThat(OrderPlaced()).isNotEqualTo("not an event")
        }
    }

    @Nested
    inner class `occurredAt` {
        @Test
        fun `is when it was created`() {
            Clock.stoppedAt(LocalDateTime.of(2026, 9, 20, 12, 30))

            assertThat(OrderPlaced().occurredAt).isEqualTo(LocalDateTime.of(2026, 9, 20, 12, 30))
        }
    }

    @Nested
    inner class `eventType` {
        @Test
        fun `is the class name when nothing says otherwise`() {
            assertThat(OrderPlaced().eventType).isEqualTo("OrderPlaced")
        }

        @Test
        fun `is what the annotation says, so a class can be renamed`() {
            assertThat(OrderShipped().eventType).isEqualTo("order.shipped.v2")
        }

        @Test
        fun `is the same answer every time, since it is cached by class`() {
            assertThat(OrderPlaced().eventType).isEqualTo(OrderPlaced().eventType)
        }

        @Test
        fun `an anonymous class has no name to serialize, and says so`() {
            assertThatThrownBy { (object {})::class.eventType() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Use @EventType")
        }
    }

    @Nested
    inner class `toString` {
        @Test
        fun `shows the class, the id and when it happened`() {
            val event = OrderPlaced()

            assertThat(event.toString())
                .startsWith("OrderPlaced(id=${event.id}")
                .contains("occurredAt=${event.occurredAt}")
        }
    }

    @AfterEach
    fun letTheClockRunAgain() {
        Clock.live()
    }

    private class OrderPlaced: Event {
        constructor(): super()
        constructor(id: UUID): super(id)
    }

    private class OrderCancelled(id: UUID): Event(id)

    @EventType("order.shipped.v2")
    private class OrderShipped: Event()
}
