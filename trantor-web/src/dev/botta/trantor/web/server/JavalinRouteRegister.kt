package dev.botta.trantor.web.server

import io.javalin.Javalin
import io.javalin.http.*
import io.javalin.websocket.WsConfig
import io.opentelemetry.api.OpenTelemetry
import java.util.function.Consumer

class JavalinRouteRegister(
    private val javalin: Javalin,
    override val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): RouteRegister {
    override fun before(handler: Handler) = apply {
        javalin.before(handler)
    }

    override fun beforeMatched(handler: Handler) = apply {
        javalin.beforeMatched(handler)
    }

    override fun after(handler: Handler) = apply {
        javalin.after(handler)
    }

    override fun afterMatched(handler: Handler) = apply {
        javalin.afterMatched(handler)
    }

    override fun post(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.POST, path, handler)
    }

    override fun get(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.GET, path, handler)
    }

    override fun put(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.PUT, path, handler)
    }

    override fun patch(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.PATCH, path, handler)
    }

    override fun delete(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.DELETE, path, handler)
    }

    override fun ws(path: String, consumer: Consumer<WsConfig>) = apply {
        javalin.ws(path, consumer)
    }

    override fun wsBefore(consumer: Consumer<WsConfig>) = apply {
        javalin.wsBefore(consumer)
    }

    override fun wsAfter(consumer: Consumer<WsConfig>) = apply {
        javalin.wsAfter(consumer)
    }

    private fun registerRoute(verb: HandlerType, path: String, handler: Handler) {
        javalin.addHttpHandler(verb, path, handler)
    }
}
