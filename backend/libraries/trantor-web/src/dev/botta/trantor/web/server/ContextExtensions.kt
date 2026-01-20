package dev.botta.trantor.web.server

import dev.botta.json.Json
import dev.botta.json.values.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context

fun Context.jsonValue(json: JsonValue = JsonObject()) {
    contentType("application/json")
    result(json.toString())
}

fun Context.jsonObj(vararg pairs: Pair<String, Any?>) {
    jsonValue(Json.obj(pairs.toList()))
}

fun Context.jsonBody() = Json.parse(body().ifEmpty { "{}" }).asObject() ?: throw BadRequestResponse("Empty body")

fun Context.jsonError(e: Exception, message: String = e.message ?: "") {
    jsonError(e.javaClass.simpleName, message)
}

fun Context.jsonError(type: String, message: String) {
    contentType("application/json")
    jsonObj("type" to type, "message" to message)
}
