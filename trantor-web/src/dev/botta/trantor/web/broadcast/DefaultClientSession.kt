package dev.botta.trantor.web.broadcast

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.cqbus.identity.Identity
import dev.botta.time.Clock
import dev.botta.trantor.web.broadcast.ws.WebSocketClientSession
import io.javalin.websocket.WsContext
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

class DefaultClientSession(
    override val identity: Identity,
    override val wsContext: WsContext,
): WebSocketClientSession {
    override val id: UUID = UuidCreator.getTimeOrderedEpoch()
    override val createdAt: LocalDateTime = Clock.now()
    // Copy on write and not a plain set: the broadcaster iterates the subscriptions of a session while
    // the websocket thread of that same session joins or leaves. Its iterator is a snapshot, so reading
    // and writing at once is neither a ConcurrentModificationException nor a corrupted set
    private val _channelSubscriptions = CopyOnWriteArraySet<String>()
    override val channelSubscriptions: Set<String> get() = _channelSubscriptions
    @Volatile
    private var timeoutAt: LocalDateTime = createdAt.plusSeconds(SESSION_TIMEOUT_SECS)

    override fun setAttribute(key: String, value: Any?) = wsContext.attribute(key, value)

    override fun <T> getAttribute(key: String): T? = wsContext.attribute(key)

    override fun getAttributeKeys(): Set<String> = wsContext.attributeMap().keys

    override fun send(message: String) {
        wsContext.send(message)
    }

    override fun join(channel: String) {
        _channelSubscriptions.add(channel)
    }

    override fun leave(channel: String) {
        _channelSubscriptions.remove(channel)
    }

    override fun touch() {
        timeoutAt = Clock.now().plusSeconds(SESSION_TIMEOUT_SECS)
    }

    override fun isExpired() = Clock.now().isAfter(timeoutAt)
}
