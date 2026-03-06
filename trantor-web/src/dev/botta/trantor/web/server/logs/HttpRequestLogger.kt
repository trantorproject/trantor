package dev.botta.trantor.web.server.logs

import io.javalin.http.Context

fun interface HttpRequestLogger {
    @Throws(Exception::class)
    fun handle(ctx: Context, executionTimeMs: Float)
}
