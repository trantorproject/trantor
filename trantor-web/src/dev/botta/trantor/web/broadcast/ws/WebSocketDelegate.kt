package dev.botta.trantor.web.broadcast.ws

import io.javalin.websocket.WsConnectContext

interface WebSocketDelegate {
    fun createSession(wsContext: WsConnectContext): WebSocketClientSession

    fun connect(session: WebSocketClientSession)

    fun onClientMessage(message: String, session: WebSocketClientSession)

    fun close(session: WebSocketClientSession)
}
