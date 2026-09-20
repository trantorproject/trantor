@file:Suppress("ClassName")

package dev.botta.trantor.core.events.serialization

import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.events.EventType
import dev.botta.trantor.primitives.events.serialization.EventClassNotFound
import dev.botta.trantor.primitives.events.serialization.SerializedEvent
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultEventSerializerTest {
    @Nested
    inner class `round trip` {
        @Test
        fun `an event comes back with its contents`() {
            serializer.register(OrderPlaced::class)
            val event = OrderPlaced("order-7", 150)

            val back = serializer.deserialize(serializer.serialize(event)) as OrderPlaced

            assertThat(back.orderId).isEqualTo("order-7")
            assertThat(back.total).isEqualTo(150)
        }

        @Test
        fun `and with the same identity, so a redelivery is not a new event`() {
            serializer.register(OrderPlaced::class)
            val event = OrderPlaced("order-7", 150)

            val back = serializer.deserialize(serializer.serialize(event))

            assertThat(back.id).isEqualTo(event.id)
            assertThat(back).isEqualTo(event)
        }

        @Test
        fun `what is stored is the event type, not the class name`() {
            serializer.register(OrderShipped::class)

            assertThat(serializer.serialize(OrderShipped()).type).isEqualTo("order.shipped.v2")
        }
    }

    @Nested
    inner class `registering` {
        @Test
        fun `says whether a class is known`() {
            assertThat(serializer.isRegistered(OrderPlaced::class)).isFalse()

            serializer.register(OrderPlaced::class)

            assertThat(serializer.isRegistered(OrderPlaced::class)).isTrue()
        }

        @Test
        fun `the same class twice is not a problem`() {
            serializer.register(OrderPlaced::class)
            serializer.register(OrderPlaced::class)

            assertThat(serializer.isRegistered(OrderPlaced::class)).isTrue()
        }

        @Test
        fun `two classes with one name would silently deserialize wrong, so it stops`() {
            serializer.register(OrderPlaced::class)

            assertThatThrownBy { serializer.register(Shipping.OrderPlaced::class) }
                .isInstanceOf(EventTypeCollisionError::class.java)
                .hasMessageContaining("Use @EventType")
        }
    }

    @Nested
    inner class `a type nobody registered` {
        @Test
        fun `cannot be deserialized, and says which one it was`() {
            assertThatThrownBy { serializer.deserialize(SerializedEvent("OrderPlaced", "{}")) }
                .isInstanceOf(EventClassNotFound::class.java)
                .hasMessageContaining("OrderPlaced")
        }

        @Test
        fun `can still be serialized, because writing needs no registry`() {
            assertThat(serializer.serialize(OrderPlaced("order-7", 150)).type).isEqualTo("OrderPlaced")
        }
    }

    private class OrderPlaced(val orderId: String = "", val total: Int = 0): Event()

    @EventType("order.shipped.v2")
    private class OrderShipped: Event()

    /** Another class with the same simple name, which is what a collision looks like in practice. */
    private object Shipping {
        class OrderPlaced: Event()
    }

    private val serializer = DefaultEventSerializer(GsonSerializer())
}
