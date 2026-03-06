package dev.botta.trantor.web.application.routes

import dev.botta.cqbus.requests.Request
import dev.botta.trantor.web.server.RouteRegistrant
import io.javalin.http.Handler
import io.javalin.websocket.WsConfig
import java.util.function.Consumer
import kotlin.reflect.KClass

interface ApplicationRouteRegistrant: RouteRegistrant {
    override val routes: ApplicationRouteRegister
}

fun ApplicationRouteRegistrant.before(handler: Handler) = apply {
    routes.before(handler)
}

fun ApplicationRouteRegistrant.beforeMatched(handler: Handler) = apply {
    routes.beforeMatched(handler)
}

fun ApplicationRouteRegistrant.after(handler: Handler) = apply {
    routes.after(handler)
}

fun ApplicationRouteRegistrant.afterMatched(handler: Handler) = apply {
    routes.afterMatched(handler)
}

fun ApplicationRouteRegistrant.post(path: String, handler: Handler) = apply {
    routes.post(path, handler)
}

fun <T: Request<R>, R> ApplicationRouteRegistrant.post(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
    routes.post(path, requestType, statusCode)
}

fun ApplicationRouteRegistrant.get(path: String, handler: Handler) = apply {
    routes.get(path, handler)
}

fun <T: Request<R>, R> ApplicationRouteRegistrant.get(path: String, requestType: KClass<T>) = apply {
    routes.get(path, requestType)
}

fun ApplicationRouteRegistrant.put(path: String, handler: Handler) = apply {
    routes.put(path, handler)
}

fun <T: Request<R>, R> ApplicationRouteRegistrant.put(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
    routes.put(path, requestType, statusCode)
}

fun ApplicationRouteRegistrant.patch(path: String, handler: Handler) = apply {
    routes.patch(path, handler)
}

fun <T: Request<R>, R> ApplicationRouteRegistrant.patch(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
    routes.patch(path, requestType, statusCode)
}

fun ApplicationRouteRegistrant.delete(path: String, handler: Handler) = apply {
    routes.delete(path, handler)
}

fun <T: Request<R>, R> ApplicationRouteRegistrant.delete(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
    routes.delete(path, requestType, statusCode)
}

fun ApplicationRouteRegistrant.ws(path: String, consumer: Consumer<WsConfig>) = apply {
    routes.ws(path, consumer)
}

fun ApplicationRouteRegistrant.wsBefore(consumer: Consumer<WsConfig>) = apply {
    routes.wsBefore(consumer)
}

fun ApplicationRouteRegistrant.wsAfter(consumer: Consumer<WsConfig>) = apply {
    routes.wsAfter(consumer)
}
