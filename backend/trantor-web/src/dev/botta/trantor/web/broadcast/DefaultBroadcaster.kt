package dev.botta.trantor.web.broadcast

import dev.botta.cqbus.identity.*
import dev.botta.json.Json
import dev.botta.trantor.core.broadcast.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.web.broadcast.ws.*
import dev.botta.trantor.web.server.HttpServer
import io.javalin.websocket.WsContext
import java.util.concurrent.*

class DefaultBroadcaster(
    wsPath: String = "/broadcaster",
    httpServer: HttpServer,
    private val serializer: JsonSerializer,
): Broadcaster, HostedService {
    private val logger = getLogger()
    private val sessionManager = SessionManager()
    private val channelRegistry = ChannelRegistry()
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "ws-sweeper").apply { isDaemon = true }
    }
    private val wsHandler = WebSocketHandler(wsPath, object: WebSocketDelegate {
        override fun authenticate(wsContext: WsContext): Identity {
            return AnonymousIdentity()
        }

        override fun connect(session: DefaultClientSession) {
            sessionManager.add(session)
        }

        override fun onClientMessage(message: String, session: DefaultClientSession) {
            try {
                val messageJson = Json.parse(message).asObject()
                if (messageJson == null) {
                    send(session, WebSocketErrorMessage("invalid-message", "Message must be json object: $message"))
                    return
                }
                val type = messageJson["type"]?.asString()
                if (type == null) {
                    send(session, WebSocketErrorMessage("invalid-message", "Message must have type property: $message"))
                    return
                }
                when (type) {
                    "join" -> {
                        val channel = messageJson["channel"]?.asString()
                        if (channel == null) {
                            send(
                                session,
                                WebSocketErrorMessage("join-error", "Message must have channel property: $message")
                            )
                            return
                        }
                        requestJoin(channel, session)
                    }

                    "leave" -> {
                        val channel = messageJson["channel"]?.asString()
                        if (channel == null) {
                            send(
                                session,
                                WebSocketErrorMessage("leave-error", "Message must have channel property: $message")
                            )
                            return
                        }
                        requestLeave(channel, session)
                    }

                    "ping" -> {}
                    else -> send(session, WebSocketErrorMessage("invalid-message", "Unsupported message type '$type'"))
                }
            } catch (e: Exception) {
                logger.error(e.message, e)
                send(session, WebSocketErrorMessage("internal-error", "Internal error processing message: $message"))
            }
        }

        override fun close(session: DefaultClientSession) {
            sessionManager.remove(session)
        }
    })

    init {
        wsHandler.register(httpServer)
    }

    override fun send(channel: String, event: Event) {
        val sessions = getSubscribers(channel)
        val message = serializer.serialize(WebSocketEventMessage(channel, event))
        sessions.forEach {
            send(it as DefaultClientSession, message)
        }
    }

    private fun send(session: DefaultClientSession, message: WebSocketMessage) {
        send(session, serializer.serialize(message))
    }

    private fun send(session: DefaultClientSession, message: String) {
        try {
            session.send(message)
        } catch (e: Exception) {
            logger.error("send failed: ${e.message}", e)
        }
    }

    override fun register(channel: Channel) {
        channelRegistry.add(channel)
    }

    override fun getSubscribers(channel: String): List<ClientSession> {
        return sessionManager.all.filter { it.channelSubscriptions.contains(channel) }
    }

    override fun getSessions(identity: Identity): List<ClientSession> {
        return sessionManager.all.filter { it.identity == identity }
    }

    override fun start() {
        scheduler.scheduleWithFixedDelay(
            { sweepIdleSessions() },
            SWEEP_INTERVAL_SECS, SWEEP_INTERVAL_SECS, TimeUnit.SECONDS
        )
    }

    override fun stop(timeoutSeconds: Int) {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt() // must restore interrupted flag
        }
    }

    private fun sweepIdleSessions() {
        try {
            sessionManager.all.filter { it.isExpired() }.forEach { sessionManager.remove(it) }
        } catch (e: Exception) {
            logger.error("sweepIdleSessions error: ${e.message}", e)
        }
    }

    private fun requestJoin(channel: String, session: DefaultClientSession) {
        val match = channelRegistry.match(channel)
        if (match == null) {
            send(session, WebSocketErrorMessage("join-error", "Invalid channel: $channel", mapOf("channel" to channel)))
            return
        }
        val isAuthorized = match.channel.authorize(match.params, session.identity)
        if (!isAuthorized) {
            send(session, WebSocketErrorMessage("join-forbidden", "Unauthorized", mapOf("channel" to channel)))
            return
        }
        session.join(channel)
        match.channel.onJoin(match.params, session)
        send(session, WebSocketInternalMessage("joined", mapOf("channel" to channel)))
    }

    private fun requestLeave(channel: String, session: DefaultClientSession) {
        val match = channelRegistry.match(channel)
        if (match == null) {
            send(session, WebSocketErrorMessage("leave-error", "Invalid channel: $channel", mapOf("channel" to channel)))
            return
        }
        if (!session.channelSubscriptions.contains(channel)) return
        session.leave(channel)
        match.channel.onLeave(match.params, session)
        send(session, WebSocketInternalMessage("leaved", mapOf("channel" to channel)))
    }
}
