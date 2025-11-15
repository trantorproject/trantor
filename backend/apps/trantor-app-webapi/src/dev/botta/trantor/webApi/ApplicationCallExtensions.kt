package dev.botta.trantor.webApi

import dev.botta.trantor.web.server.respondJsonObj
import io.ktor.http.*
import io.ktor.server.application.*

suspend fun ApplicationCall.respondJsonError(e: Throwable, message: String = e.message ?: "", status: HttpStatusCode = HttpStatusCode.InternalServerError) {
    respondJsonError(e.javaClass.simpleName, message, status)
}

suspend fun ApplicationCall.respondJsonError(type: String, message: String, status: HttpStatusCode = HttpStatusCode.InternalServerError) {
    response.status(status)
    respondJsonObj("type" to type, "message" to message)
}
