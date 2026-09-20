@file:Suppress("ClassName")

package dev.botta.trantor.domain.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RecordedEventsTest {
    @Nested
    inner class `recording` {
        @Test
        fun `keeps what happened, in the order it happened`() {
            val placed = OrderPlaced()
            val shipped = OrderShipped()

            recorded.trigger(placed)
            recorded.trigger(shipped)

            assertThat(recorded).containsExactly(placed, shipped)
        }

        @Test
        fun `several at once keeps the order too`() {
            val placed = OrderPlaced()
            val shipped = OrderShipped()

            recorded.triggerAll(listOf(placed, shipped))

            assertThat(recorded).containsExactly(placed, shipped)
        }

        @Test
        fun `nothing recorded is an empty list, not a null`() {
            assertThat(recorded).isEmpty()
            assertThat(recorded.size).isZero()
        }

        @Test
        fun `behaves as the list it says it is`() {
            val placed = OrderPlaced()
            recorded.trigger(placed)

            assertThat(recorded[0]).isEqualTo(placed)
            assertThat(recorded.contains(placed)).isTrue()
            assertThat(recorded.indexOf(placed)).isZero()
        }
    }

    @Nested
    inner class `consume` {
        @Test
        fun `hands over what was recorded`() {
            val placed = OrderPlaced()
            recorded.trigger(placed)

            assertThat(recorded.consume()).containsExactly(placed)
        }

        @Test
        fun `and leaves nothing behind, so nothing is published twice`() {
            recorded.trigger(OrderPlaced())

            recorded.consume()

            assertThat(recorded).isEmpty()
        }

        @Test
        fun `what was handed over is not affected by what happens next`() {
            recorded.trigger(OrderPlaced())

            val consumed = recorded.consume()
            recorded.trigger(OrderShipped())

            assertThat(consumed).hasSize(1)
        }

        @Test
        fun `with nothing recorded it hands over nothing`() {
            assertThat(recorded.consume()).isEmpty()
        }
    }

    @Nested
    inner class `clear` {
        @Test
        fun `drops what was recorded without handing it over`() {
            recorded.trigger(OrderPlaced())

            recorded.clear()

            assertThat(recorded).isEmpty()
        }
    }

    @Nested
    inner class `equality` {
        @Test
        fun `is what was recorded`() {
            val placed = OrderPlaced()
            recorded.trigger(placed)
            val other = RecordedEvents().apply { trigger(placed) }

            assertThat(recorded).isEqualTo(other)
            assertThat(recorded.hashCode()).isEqualTo(other.hashCode())
        }

        @Test
        fun `two aggregates that did different things are not equal`() {
            recorded.trigger(OrderPlaced())
            val other = RecordedEvents().apply { trigger(OrderPlaced()) }

            assertThat(recorded).isNotEqualTo(other)
        }
    }

    private class OrderPlaced: DomainEvent()

    private class OrderShipped: DomainEvent()

    private val recorded = RecordedEvents()
}
