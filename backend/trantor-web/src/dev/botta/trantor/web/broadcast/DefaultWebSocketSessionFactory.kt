package dev.botta.trantor.web.broadcast

import dev.botta.cqbus.identity.AnonymousIdentity
import dev.botta.trantor.web.broadcast.ws.WebSocketClientSession
import io.javalin.websocket.WsConnectContext

class DefaultWebSocketSessionFactory: WebSocketClientSessionFactory {
    override fun create(ctx: WsConnectContext): WebSocketClientSession {
        return DefaultClientSession(AnonymousIdentity(), ctx)
    }
}
