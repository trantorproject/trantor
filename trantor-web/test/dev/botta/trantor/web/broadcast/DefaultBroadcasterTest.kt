@file:Suppress("ClassName")

package dev.botta.trantor.web.broadcast

import dev.botta.cqbus.identity.Identity
import dev.botta.json.Json
import dev.botta.trantor.core.broadcast.Channel
import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.server.HttpServer
import dev.botta.trantor.web.testing.FakeClientSession
import io.javalin.websocket.WsConnectContext
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultBroadcasterTest {
    @Nested
    inner class `sending to a channel` {
        @Test
        fun `reaches whoever is subscribed`() {
            val subscribed = connected().apply { join("orders") }

            broadcaster.send("orders", OrderPlaced())

            assertThat(subscribed.sent).hasSize(1)
        }

        @Test
        fun `does not reach whoever is not`() {
            val elsewhere = connected().apply { join("invoices") }

            broadcaster.send("orders", OrderPlaced())

            assertThat(elsewhere.sent).isEmpty()
        }

        @Test
        fun `does not reach a client subscribed to nothing`() {
            val idle = connected()

            broadcaster.send("orders", OrderPlaced())

            assertThat(idle.sent).isEmpty()
        }

        @Test
        fun `reaches everyone subscribed`() {
            val one = connected().apply { join("orders") }
            val other = connected().apply { join("orders") }

            broadcaster.send("orders", OrderPlaced())

            assertThat(one.sent).hasSize(1)
            assertThat(other.sent).hasSize(1)
        }

        @Test
        fun `a channel nobody is on sends nothing, without failing`() {
            connected()

            broadcaster.send("orders", OrderPlaced())
        }
    }

    @Nested
    inner class `sending to several channels` {
        @Test
        fun `reaches the union of their subscribers`() {
            val onOrders = connected().apply { join("orders") }
            val onInvoices = connected().apply { join("invoices") }

            broadcaster.send(listOf("orders", "invoices"), OrderPlaced())

            assertThat(onOrders.sent).hasSize(1)
            assertThat(onInvoices.sent).hasSize(1)
        }

        @Test
        fun `someone on both gets it once, not twice`() {
            val onBoth = connected().apply { join("orders"); join("invoices") }

            broadcaster.send(listOf("orders", "invoices"), OrderPlaced())

            assertThat(onBoth.sent).hasSize(1)
        }
    }

    @Nested
    inner class `the message` {
        @Test
        fun `says it is an event, and which channels it went to`() {
            val subscribed = connected().apply { join("orders") }

            broadcaster.send("orders", OrderPlaced())

            val message = Json.parse(subscribed.sent.first()).asObject()!!
            assertThat(message["type"]?.asString()).isEqualTo("event")
            assertThat(message["channels"]?.asArray()?.map { it.asString() }).containsExactly("orders")
        }

        @Test
        fun `carries the event itself`() {
            val subscribed = connected().apply { join("orders") }

            broadcaster.send("orders", OrderPlaced("order-7"))

            val event = Json.parse(subscribed.sent.first()).asObject()!!["event"]?.asObject()
            assertThat(event?.get("orderId")?.asString()).isEqualTo("order-7")
            assertThat(event?.get("eventType")?.asString()).isEqualTo("OrderPlaced")
        }
    }

    @Nested
    inner class `a client whose socket died` {
        @Test
        fun `does not stop the message reaching the others`() {
            connect(FakeClientSession(failsToSend = true).apply { join("orders") })
            val alive = connected().apply { join("orders") }

            broadcaster.send("orders", OrderPlaced())

            assertThat(alive.sent).hasSize(1)
        }
    }

    @Nested
    inner class `looking sessions up` {
        @Test
        fun `by channel`() {
            val subscribed = connected().apply { join("orders") }
            connected().apply { join("invoices") }

            assertThat(broadcaster.getSubscribers("orders")).containsExactly(subscribed)
        }

        @Test
        fun `by identity, because one person may have several tabs open`() {
            val nico = NamedIdentity("nico")
            val one = connect(FakeClientSession(identity = nico))
            val other = connect(FakeClientSession(identity = nico))
            connect(FakeClientSession(identity = NamedIdentity("someone else")))

            assertThat(broadcaster.getSessions(nico)).containsExactlyInAnyOrder(one, other)
        }

        @Test
        fun `an identity nobody is connected with finds nothing`() {
            connected()

            assertThat(broadcaster.getSessions(NamedIdentity("nobody"))).isEmpty()
        }
    }

    @Nested
    inner class `registering a channel` {
        @Test
        fun `makes its path matchable, which is what lets a client join it`() {
            broadcaster.register(Orders())

            assertThat(channels.match("orders")?.channel).isInstanceOf(Orders::class.java)
        }

        @Test
        fun `one that was not registered cannot be joined`() {
            broadcaster.register(Orders())

            assertThat(channels.match("invoices")).isNull()
        }
    }

    private fun connected() = connect(FakeClientSession())

    private fun <T: FakeClientSession> connect(session: T) = session.also { sessions.add(it) }

    private class Orders: Channel("orders")

    private class OrderPlaced(val orderId: String = "order-1"): Event()

    private data class NamedIdentity(override val name: String): Identity {
        override val isAuthenticated = true
        override val authenticationType = "test"
        override val roles = emptyList<String>()
        override val properties = emptyMap<String, Any>()
    }

    // A client only gets in through a real websocket, so the test puts it in the session manager directly
    private val sessions = SessionManager()
    private val channels = ChannelRegistry()

    private val broadcaster = DefaultBroadcaster(
        "/broadcaster",
        mockk<HttpServer>(relaxed = true),
        GsonSerializer(),
        object: WebSocketClientSessionFactory {
            override fun create(ctx: WsConnectContext) = FakeClientSession()
        },
        sessions,
        channels,
    )
}
