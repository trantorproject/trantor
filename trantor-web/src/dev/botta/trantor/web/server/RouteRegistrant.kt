package dev.botta.trantor.web.server

import io.javalin.http.Handler
import io.javalin.websocket.WsConfig
import java.util.function.Consumer

interface RouteRegistrant {
    val routes: RouteRegister
}

fun RouteRegistrant.before(handler: Handler) = apply {
    routes.before(handler)
}

fun RouteRegistrant.beforeMatched(handler: Handler) = apply {
    routes.beforeMatched(handler)
}

fun RouteRegistrant.after(handler: Handler) = apply {
    routes.after(handler)
}

fun RouteRegistrant.afterMatched(handler: Handler) = apply {
    routes.afterMatched(handler)
}

fun RouteRegistrant.post(path: String, handler: Handler) = apply {
    routes.post(path, handler)
}

fun RouteRegistrant.get(path: String, handler: Handler) = apply {
    routes.get(path, handler)
}

fun RouteRegistrant.put(path: String, handler: Handler) = apply {
    routes.put(path, handler)
}

fun RouteRegistrant.patch(path: String, handler: Handler) = apply {
    routes.patch(path, handler)
}

fun RouteRegistrant.delete(path: String, handler: Handler) = apply {
    routes.delete(path, handler)
}

fun RouteRegistrant.ws(path: String, consumer: Consumer<WsConfig>) = apply {
    routes.ws(path, consumer)
}

fun RouteRegistrant.wsBefore(consumer: Consumer<WsConfig>) = apply {
    routes.wsBefore(consumer)
}

fun RouteRegistrant.wsAfter(consumer: Consumer<WsConfig>) = apply {
    routes.wsAfter(consumer)
}
