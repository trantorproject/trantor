package dev.botta.trantor.web.server.logs

import io.ktor.server.routing.*

fun interface HttpRequestLogger {
    @Throws(Exception::class)
    fun handle(ctx: RoutingContext, executionTimeMs: Float)
}
