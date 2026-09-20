package dev.botta.trantor.web.testing

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.cqbus.identity.AnonymousIdentity
import dev.botta.cqbus.identity.Identity
import dev.botta.time.Clock
import dev.botta.trantor.web.broadcast.ws.WebSocketClientSession
import io.javalin.websocket.WsContext
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.*

/** A connected client that records what was sent to it, instead of holding a socket. */
class FakeClientSession(
    override val id: UUID = UuidCreator.getTimeOrderedEpoch(),
    override val identity: Identity = AnonymousIdentity(),
    private val failsToSend: Boolean = false,
): WebSocketClientSession {
    val sent = mutableListOf<String>()

    override val wsContext: WsContext = mockk(relaxed = true)
    override val createdAt: LocalDateTime = Clock.now()

    private val subscriptions = mutableSetOf<String>()
    private val attributes = mutableMapOf<String, Any?>()
    private var expired = false

    override val channelSubscriptions: Set<String> get() = subscriptions

    override fun setAttribute(key: String, value: Any?) {
        attributes[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getAttribute(key: String): T? = attributes[key] as T?

    override fun getAttributeKeys() = attributes.keys

    override fun send(message: String) {
        if (failsToSend) error("the socket is gone")

        sent.add(message)
    }

    override fun join(channel: String) {
        subscriptions.add(channel)
    }

    override fun leave(channel: String) {
        subscriptions.remove(channel)
    }

    override fun touch() {
        expired = false
    }

    override fun isExpired() = expired

    fun expire() = apply { expired = true }
}
