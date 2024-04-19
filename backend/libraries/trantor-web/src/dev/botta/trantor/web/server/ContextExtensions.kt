package dev.botta.trantor.web.server

import dev.botta.json.Json
import dev.botta.json.values.*
import io.javalin.http.Context

fun Context.jsonValue(json: JsonValue = JsonObject()) {
    contentType("application/json")
    result(json.toString())
}

fun Context.jsonObj(vararg pairs: Pair<String, Any?>) {
    jsonValue(Json.obj(pairs.toList()))
}
