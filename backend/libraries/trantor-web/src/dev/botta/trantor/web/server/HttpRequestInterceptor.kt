package dev.botta.trantor.web.server

import io.javalin.http.Context

interface HttpRequestInterceptor {
    fun onRequest(ctx: Context)
}
