package dev.botta.trantor.web.broadcast.ws

import dev.botta.cqbus.identity.Identity
import dev.botta.trantor.web.broadcast.DefaultClientSession
import io.javalin.websocket.WsContext

interface WebSocketDelegate {
    fun authenticate(wsContext: WsContext): Identity

    fun connect(session: DefaultClientSession)

    fun onClientMessage(message: String, session: DefaultClientSession)

    fun close(session: DefaultClientSession)
}
