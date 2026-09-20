@file:Suppress("ClassName")

package dev.botta.trantor.web.broadcast

import dev.botta.cqbus.identity.AnonymousIdentity
import dev.botta.time.Clock
import io.javalin.websocket.WsContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DefaultClientSessionTest {
    @Nested
    inner class `channels` {
        @Test
        fun `a session starts subscribed to nothing`() {
            assertThat(session().channelSubscriptions).isEmpty()
        }

        @Test
        fun `joining subscribes it`() {
            val session = session()

            session.join("orders")

            assertThat(session.channelSubscriptions).containsExactly("orders")
        }

        @Test
        fun `joining twice subscribes it once`() {
            val session = session()

            session.join("orders")
            session.join("orders")

            assertThat(session.channelSubscriptions).containsExactly("orders")
        }

        @Test
        fun `leaving unsubscribes it`() {
            val session = session()
            session.join("orders")

            session.leave("orders")

            assertThat(session.channelSubscriptions).isEmpty()
        }

        @Test
        fun `leaving one it never joined changes nothing`() {
            val session = session()
            session.join("orders")

            session.leave("invoices")

            assertThat(session.channelSubscriptions).containsExactly("orders")
        }
    }

    @Nested
    inner class `expiry` {
        @Test
        fun `a new session has a while to live`() {
            Clock.stoppedAt(noon)

            assertThat(session().isExpired()).isFalse()
        }

        @Test
        fun `a session nobody heard from expires`() {
            Clock.stoppedAt(noon)
            val session = session()

            Clock.stoppedAt(noon.plusSeconds(SESSION_TIMEOUT_SECS + 1))

            assertThat(session.isExpired()).isTrue()
        }

        @Test
        fun `a message from the client keeps it alive`() {
            Clock.stoppedAt(noon)
            val session = session()

            Clock.stoppedAt(noon.plusSeconds(SESSION_TIMEOUT_SECS - 1))
            session.touch()
            Clock.stoppedAt(noon.plusSeconds(SESSION_TIMEOUT_SECS + 1))

            assertThat(session.isExpired()).isFalse()
        }

        @Test
        fun `right on the timeout it is still alive`() {
            Clock.stoppedAt(noon)
            val session = session()

            Clock.stoppedAt(noon.plusSeconds(SESSION_TIMEOUT_SECS))

            assertThat(session.isExpired()).isFalse()
        }
    }

    @Nested
    inner class `identity` {
        @Test
        fun `each session is its own`() {
            assertThat(session().id).isNotEqualTo(session().id)
        }

        @Test
        fun `it remembers when it connected`() {
            Clock.stoppedAt(noon)

            assertThat(session().createdAt).isEqualTo(noon)
        }
    }

    @Nested
    inner class `talking to the client` {
        @Test
        fun `goes through the websocket`() {
            val wsContext = wsContext()

            DefaultClientSession(AnonymousIdentity(), wsContext).send("hola")

            verify { wsContext.send("hola") }
        }

        @Test
        fun `attributes live on the websocket, so they survive a handler`() {
            val wsContext = wsContext()
            val session = DefaultClientSession(AnonymousIdentity(), wsContext)

            session.setAttribute("locale", "es-AR")

            verify { wsContext.attribute("locale", "es-AR") }
        }
    }

    @AfterEach
    fun letTheClockRunAgain() {
        Clock.live()
    }

    private fun session() = DefaultClientSession(AnonymousIdentity(), wsContext())

    private fun wsContext() = mockk<WsContext>(relaxed = true).also {
        every { it.attributeMap() } returns emptyMap()
    }

    private val noon: LocalDateTime = LocalDateTime.of(2026, 9, 20, 12, 0)
}
