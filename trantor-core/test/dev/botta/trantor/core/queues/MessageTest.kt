@file:Suppress("ClassName")

package dev.botta.trantor.core.queues

import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A driver like SQS serializes the whole message, so what is on a queue during a deploy has the old shape. */
class MessageTest {
    @Nested
    inner class `read back from a queue` {
        @Test
        fun `keeps the trace context it travelled with`() {
            val message = Message("SendEmail", "{}", "abc123", mapOf("traceparent" to TRACEPARENT))

            val read = serializer.deserialize<Message>(serializer.serialize(message))

            assertThat(read).isEqualTo(message)
        }

        @Test
        fun `enqueued before messages had a trace context has an empty one, not a null`() {
            val read = serializer.deserialize<Message>("""{"type":"SendEmail","body":"{}","cid":"abc123"}""")

            assertThat(read.traceContext).isNotNull().isEmpty()
            assertThat(read.cid).isEqualTo("abc123")
        }
    }

    private val serializer = GsonSerializer()

    private companion object {
        const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }
}
