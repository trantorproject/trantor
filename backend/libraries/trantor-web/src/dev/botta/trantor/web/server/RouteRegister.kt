package dev.botta.trantor.web.server

import io.javalin.Javalin
import io.javalin.http.*
import io.javalin.websocket.WsConfig
import java.util.function.Consumer

class RouteRegister(private val javalin: Javalin) {
    fun before(handler: Handler) = apply {
        javalin.before(handler)
    }

    fun after(handler: Handler) = apply {
        javalin.after(handler)
    }

    fun post(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.POST, path, handler)
    }

    fun get(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.GET, path, handler)
    }

    fun put(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.PUT, path, handler)
    }

    fun patch(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.PATCH, path, handler)
    }

    fun delete(path: String, handler: Handler) = apply {
        registerRoute(HandlerType.DELETE, path, handler)
    }

    fun ws(path: String, consumer: Consumer<WsConfig>) = apply {
        javalin.ws(path, consumer)
    }

    fun wsBefore(path: String, consumer: Consumer<WsConfig>) = apply {
        javalin.wsBefore(path, consumer)
    }

    fun wsAfter(path: String, consumer: Consumer<WsConfig>) = apply {
        javalin.wsAfter(path, consumer)
    }

    private fun registerRoute(verb: HandlerType, path: String, handler: Handler) {
        javalin.addHttpHandler(verb, path, handler)
    }
}
