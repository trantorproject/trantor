@file:Suppress("ClassName")

package dev.botta.trantor.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*

class IdTest {
    @Nested
    inner class `a new id` {
        @Test
        fun `is its own`() {
            assertThat(OrderId()).isNotEqualTo(OrderId())
        }

        @Test
        fun `can be read back as a uuid, for whatever stores it`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw).toUUID()).isEqualTo(raw)
        }

        @Test
        fun `reads as the uuid that spells it`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw).toString()).isEqualTo(raw.toString())
        }
    }

    @Nested
    inner class `from text` {
        @Test
        fun `is the same id as from the uuid`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw.toString())).isEqualTo(OrderId(raw))
        }

        @Test
        fun `text that is not a uuid is refused, not kept as a broken id`() {
            assertThatThrownBy { OrderId("not-a-uuid") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class `equality` {
        @Test
        fun `is the value it holds`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw)).isEqualTo(OrderId(raw))
            assertThat(OrderId(raw).hashCode()).isEqualTo(OrderId(raw).hashCode())
        }

        @Test
        fun `an order and a customer are never the same thing, whatever the uuid`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw)).isNotEqualTo(CustomerId(raw))
        }

        @Test
        fun `an id is not the uuid inside it`() {
            val raw = UUID.randomUUID()

            assertThat(OrderId(raw)).isNotEqualTo(raw)
        }
    }

    private class OrderId: Id {
        constructor(raw: UUID): super(raw)
        constructor(raw: String): super(raw)
        constructor(): super()
    }

    private class CustomerId(raw: UUID): Id(raw)
}
