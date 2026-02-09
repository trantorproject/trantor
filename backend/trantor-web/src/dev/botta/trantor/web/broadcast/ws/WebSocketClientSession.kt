package dev.botta.trantor.web.broadcast.ws

import dev.botta.trantor.core.broadcast.ClientSession
import io.javalin.websocket.WsContext

interface WebSocketClientSession: ClientSession {
    val wsContext: WsContext

    fun send(message: String)

    fun join(channel: String)

    fun leave(channel: String)

    fun touch()

    fun isExpired(): Boolean
}
