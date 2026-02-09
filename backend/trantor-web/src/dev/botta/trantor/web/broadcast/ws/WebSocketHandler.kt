package dev.botta.trantor.web.broadcast.ws

import dev.botta.trantor.domain.errors.ForbiddenError
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.web.broadcast.*
import dev.botta.trantor.web.server.*
import io.javalin.websocket.*

class WebSocketHandler(private val path: String, private val delegate: WebSocketDelegate) {
    private val logger = getLogger()

    fun register(httpServer: HttpServer) {
        httpServer.ws(path) {
            it.onConnect { ctx -> onClientConnect(ctx) }
            it.onMessage { ctx -> onClientMessage(ctx) }
            it.onError { ctx -> onClientError(ctx) }
            it.onClose { ctx -> onClientClose(ctx) }
        }
    }

    private fun onClientConnect(ctx: WsConnectContext) {
        try {
            val identity = delegate.authenticate(ctx)
            val session = DefaultClientSession(identity, ctx)
            ctx.attribute("session", session)
            delegate.connect(session)
            logger.info("Client connected ${session.id} with identity ${identity.name}")
        } catch (e: ForbiddenError) {
            logger.info("Client connection rejected because unauthorized: ${e.message}")
            ctx.session.close(POLICY_VIOLATION_CODE, "unauthorized")
        }
    }

    private fun onClientMessage(ctx: WsMessageContext) {
        val session = ctx.attribute<DefaultClientSession>("session") ?: return
        session.touch()
        try {
            delegate.onClientMessage(ctx.message(), session)
        } catch (e: Exception) {
            logger.error("Websocket error: ${e.message}", e)
            removeAndClose(session, "process message", PROTOCOL_ERROR_CODE)
        }
    }

    private fun onClientClose(ctx: WsCloseContext) {
        val session = ctx.attribute<DefaultClientSession>("session") ?: return
        val identity = session.identity
        delegate.close(session)
        logger.info("Client disconnected${session.id} with identity ${identity.name}")
    }

    private fun onClientError(ctx: WsErrorContext) {
        logger.info("Websocket error: ${ctx.error()?.message}")
        val session = ctx.attribute<DefaultClientSession>("session") ?: return
        removeAndClose(session, "error", PROTOCOL_ERROR_CODE)
    }

    private fun removeAndClose(session: DefaultClientSession, reason: String, code: Int = NORMAL_CLOSURE_CODE) {
        try {
            session.wsContext.session.close(code, reason)
        } catch (_: Exception) {
        }
        delegate.close(session)
    }
}
