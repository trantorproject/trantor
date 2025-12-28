package dev.botta.trantor.web.server

import io.javalin.http.Handler

interface RouteRegistrant {
    val routes: RouteRegister
}

fun RouteRegistrant.before(handler: Handler): RouteRegistrant {
    routes.before(handler)
    return this
}

fun RouteRegistrant.post(path: String, handler: Handler): RouteRegistrant {
    routes.post(path, handler)
    return this
}

fun RouteRegistrant.get(path: String, handler: Handler): RouteRegistrant {
    routes.get(path, handler)
    return this
}

fun RouteRegistrant.put(path: String, handler: Handler): RouteRegistrant {
    routes.put(path, handler)
    return this
}

fun RouteRegistrant.patch(path: String, handler: Handler): RouteRegistrant {
    routes.patch(path, handler)
    return this
}

fun RouteRegistrant.delete(path: String, handler: Handler): RouteRegistrant {
    routes.delete(path, handler)
    return this
}
