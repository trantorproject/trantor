package dev.botta.trantor.web.server

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*

class RouteRegister {
    private val configurations = mutableListOf<(Routing.() -> Unit)>()

    fun get(path: String, handler: RoutingHandler) = apply {
        registerRoute(HttpMethod.Get, path, handler)
    }

    fun post(path: String, handler: RoutingHandler) = apply {
        registerRoute(HttpMethod.Post, path, handler)
    }

    fun put(path: String, handler: RoutingHandler) = apply {
        registerRoute(HttpMethod.Put, path, handler)
    }

    fun patch(path: String, handler: RoutingHandler) = apply {
        registerRoute(HttpMethod.Patch, path, handler)
    }

    fun delete(path: String, handler: RoutingHandler) = apply {
        registerRoute(HttpMethod.Delete, path, handler)
    }

    fun ws(path: String, handler: suspend DefaultWebSocketServerSession.() -> Unit) = apply {
        configurations.add { webSocket { handler(this) }}
    }

    private fun registerRoute(verb: HttpMethod, path: String, handler: RoutingHandler) {
        configurations.add {
            route(path, verb) { handle(handler) }
        }
    }

    fun configure(routing: Routing) {
        configurations.forEach { it(routing) }
    }
}
