package dev.botta.trantor.web.server

import io.ktor.server.routing.*

interface HttpRequestInterceptor {
    fun onRequest(ctx: RoutingContext)
}
