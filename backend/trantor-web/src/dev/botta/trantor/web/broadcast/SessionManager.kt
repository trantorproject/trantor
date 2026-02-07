package dev.botta.trantor.web.broadcast

import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SessionManager {
    private val sessionsById = ConcurrentHashMap<UUID, DefaultClientSession>()
    val all get() = sessionsById.values

    fun add(session: DefaultClientSession) {
        sessionsById[session.id] = session
    }

    fun remove(session: DefaultClientSession) {
        sessionsById.remove(session.id)
    }
}
