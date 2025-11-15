package dev.botta.trantor.web.server

import io.ktor.server.routing.*

interface RouteRegistrant {
    val routes: RouteRegister
}

fun RouteRegistrant.post(path: String, handler: RoutingHandler): RouteRegistrant {
    routes.post(path, handler)
    return this
}

fun RouteRegistrant.get(path: String, handler: RoutingHandler): RouteRegistrant {
    routes.get(path, handler)
    return this
}

fun RouteRegistrant.put(path: String, handler: RoutingHandler): RouteRegistrant {
    routes.put(path, handler)
    return this
}

fun RouteRegistrant.patch(path: String, handler: RoutingHandler): RouteRegistrant {
    routes.patch(path, handler)
    return this
}

fun RouteRegistrant.delete(path: String, handler: RoutingHandler): RouteRegistrant {
    routes.delete(path, handler)
    return this
}
