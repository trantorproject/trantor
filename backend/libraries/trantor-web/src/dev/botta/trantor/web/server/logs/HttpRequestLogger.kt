package dev.botta.trantor.web.server.logs

import io.ktor.server.application.*

fun interface HttpRequestLogger {
    @Throws(Exception::class)
    fun handle(call: ApplicationCall): String
}
