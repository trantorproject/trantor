package dev.botta.trantor.webApi

import dev.botta.trantor.web.server.jsonObj
import io.javalin.http.Context

fun Context.jsonError(e: Exception, message: String = e.message ?: "") {
    jsonError(e.javaClass.simpleName, message)
}

fun Context.jsonError(type: String, message: String) {
    contentType("application/json")
    jsonObj("type" to type, "message" to message)
}
