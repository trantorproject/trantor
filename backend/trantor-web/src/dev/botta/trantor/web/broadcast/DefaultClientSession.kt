package dev.botta.trantor.web.broadcast

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.cqbus.identity.Identity
import dev.botta.time.Clock
import dev.botta.trantor.core.broadcast.ClientSession
import io.javalin.websocket.WsContext
import java.time.LocalDateTime
import java.util.*

class DefaultClientSession(
    override val identity: Identity,
    val wsContext: WsContext,
): ClientSession {
    override val id: UUID = UuidCreator.getTimeOrderedEpoch()
    override val createdAt: LocalDateTime = Clock.now()
    private var _channelSusbcriptions = mutableSetOf<String>()
    override val channelSubscriptions: Set<String> get() = _channelSusbcriptions
    @Volatile
    private var timeoutAt: LocalDateTime = createdAt.plusSeconds(SESSION_TIMEOUT_SECS)

    override fun setAttribute(key: String, value: Any?) = wsContext.attribute(key, value)

    override fun <T> getAttribute(key: String): T? = wsContext.attribute(key)

    override fun getAttributeKeys(): Set<String> = wsContext.attributeMap().keys

    fun send(message: String) {
        wsContext.send(message)
    }

    fun join(channel: String) {
        _channelSusbcriptions.add(channel)
    }

    fun leave(channel: String) {
        _channelSusbcriptions.remove(channel)
    }

    fun touch() {
        timeoutAt = Clock.now().plusSeconds(SESSION_TIMEOUT_SECS)
    }

    fun isExpired() = Clock.now().isAfter(timeoutAt)
}
