package dev.botta.trantor.web.server.logs

import io.javalin.http.Context

fun interface HttpRequestLogger {
    @Throws(Exception::class)
    fun handle(ctx: Context, executionTimeMs: Float)
}

/**
 * The full URL of the request as a log may write it: with the values of the secret params
 * ([HttpServerSettings.secretParams][dev.botta.trantor.web.server.HttpServerSettings.secretParams]) and of the
 * signatures in its query redacted. The server leaves it on the request before it calls the logger.
 */
fun Context.loggableUrl(): String = attribute<String>(LOGGABLE_URL) ?: fullUrl()

internal const val LOGGABLE_URL = "trantor.loggableUrl"
