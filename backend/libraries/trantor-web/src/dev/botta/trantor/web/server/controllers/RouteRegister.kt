package dev.botta.trantor.web.server.controllers

import io.javalin.Javalin
import io.javalin.http.*

class RouteRegister(private val javalin: Javalin) {
    fun before(handler: Handler): RouteRegister {
        javalin.before(handler)
        return this
    }

    fun post(path: String, handler: Handler): RouteRegister {
        registerRoute(HandlerType.POST, path, handler)
        return this
    }

    fun get(path: String, handler: Handler): RouteRegister {
        registerRoute(HandlerType.GET, path, handler)
        return this
    }

    fun put(path: String, handler: Handler): RouteRegister {
        registerRoute(HandlerType.PUT, path, handler)
        return this
    }

    fun patch(path: String, handler: Handler): RouteRegister {
        registerRoute(HandlerType.PATCH, path, handler)
        return this
    }

    fun delete(path: String, handler: Handler): RouteRegister {
        registerRoute(HandlerType.DELETE, path, handler)
        return this
    }

    private fun registerRoute(verb: HandlerType, path: String, handler: Handler) {
        javalin.addHttpHandler(verb, path, handler)
    }
}
