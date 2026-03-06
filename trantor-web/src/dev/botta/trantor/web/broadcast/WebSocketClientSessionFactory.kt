package dev.botta.trantor.web.broadcast

import dev.botta.trantor.web.broadcast.ws.WebSocketClientSession
import io.javalin.websocket.WsConnectContext

interface WebSocketClientSessionFactory {
    fun create(ctx: WsConnectContext): WebSocketClientSession
}
