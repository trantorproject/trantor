package dev.botta.trantor.web.broadcast

import dev.botta.cqbus.identity.Identity
import dev.botta.json.Json
import dev.botta.trantor.core.broadcast.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.web.broadcast.ws.*
import dev.botta.trantor.web.server.HttpServer
import io.javalin.websocket.WsConnectContext
import java.util.concurrent.*

class DefaultBroadcaster(
    wsPath: String = "/broadcaster",
    httpServer: HttpServer,
    private val serializer: JsonSerializer,
    private val sessionFactory: WebSocketClientSessionFactory,
): Broadcaster, HostedService {
    private val logger = getLogger()
    private val sessionManager = SessionManager()
    private val channelRegistry = ChannelRegistry()
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "ws-sweeper").apply { isDaemon = true }
    }
    private val wsHandler = WebSocketHandler(wsPath, object: WebSocketDelegate {
        override fun createSession(wsContext: WsConnectContext): WebSocketClientSession {
            return sessionFactory.create(wsContext)
        }

        override fun connect(session: WebSocketClientSession) {
            sessionManager.add(session)
        }

        override fun onClientMessage(message: String, session: WebSocketClientSession) {
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
                        messageJson["channels"]?.asArray()?.map {
                            val channel = it.asString() ?: return@map
                            requestJoin(channel, session)
                        }
                    }

                    "leave" -> {
                        messageJson["channels"]?.asArray()?.map {
                            val channel = it.asString() ?: return@map
                            requestLeave(channel, session)
                        }
                    }

                    "ping" -> {}
                    else -> send(session, WebSocketErrorMessage("invalid-message", "Unsupported message type '$type'"))
                }
            } catch (e: Throwable) {
                logger.error(e.message, e)
                send(session, WebSocketErrorMessage("internal-error", "Internal error processing message: $message"))
            }
        }

        override fun close(session: WebSocketClientSession) {
            sessionManager.remove(session)
        }
    })

    init {
        wsHandler.register(httpServer)
    }

    override fun send(channel: String, event: Event) {
        send(listOf(channel), event)
    }

    override fun send(channels: List<String>, event: Event) {
        val sessions = mutableSetOf<ClientSession>()
        for (channel in channels) {
            sessions.addAll(getSubscribers(channel))
        }
        val message = serializer.serialize(WebSocketEventMessage(channels, event))
        sessions.forEach {
            send(it as WebSocketClientSession, message)
        }
    }

    private fun send(session: WebSocketClientSession, message: WebSocketMessage) {
        send(session, serializer.serialize(message))
    }

    private fun send(session: WebSocketClientSession, message: String) {
        try {
            session.send(message)
        } catch (e: Throwable) {
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
        } catch (e: Throwable) {
            logger.error("sweepIdleSessions error: ${e.message}", e)
        }
    }

    private fun requestJoin(channel: String, session: WebSocketClientSession) {
        val match = channelRegistry.match(channel)
        if (match == null) {
            send(session, WebSocketErrorMessage("join-error", "Invalid channel: $channel", mapOf("channel" to channel)))
            return
        }
        if (session.channelSubscriptions.contains(channel)) return
        val isAuthorized = match.channel.authorize(match.params, session)
        if (!isAuthorized) {
            send(session, WebSocketErrorMessage("join-forbidden", "Unauthorized", mapOf("channel" to channel)))
            return
        }
        session.join(channel)
        match.channel.onJoin(match.params, session)
        send(session, WebSocketInternalMessage("joined", mapOf("channel" to channel)))
    }

    private fun requestLeave(channel: String, session: WebSocketClientSession) {
        val match = channelRegistry.match(channel)
        if (match == null) {
            send(session, WebSocketErrorMessage("leave-error", "Invalid channel: $channel", mapOf("channel" to channel)))
            return
        }
        if (!session.channelSubscriptions.contains(channel)) return
        session.leave(channel)
        match.channel.onLeave(match.params, session)
        send(session, WebSocketInternalMessage("left", mapOf("channel" to channel)))
    }
}
