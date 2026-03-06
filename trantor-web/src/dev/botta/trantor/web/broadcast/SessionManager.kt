package dev.botta.trantor.web.broadcast

import dev.botta.trantor.web.broadcast.ws.WebSocketClientSession
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SessionManager {
    private val sessionsById = ConcurrentHashMap<UUID, WebSocketClientSession>()
    val all get() = sessionsById.values

    fun add(session: WebSocketClientSession) {
        sessionsById[session.id] = session
    }

    fun remove(session: WebSocketClientSession) {
        sessionsById.remove(session.id)
    }
}
