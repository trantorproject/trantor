package dev.botta.trantor.web.server

import dev.botta.json.Json
import dev.botta.json.values.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

suspend fun ApplicationCall.respondJsonValue(json: JsonValue = JsonObject(), status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(json.toString(), ContentType.Application.Json, status)
}

suspend fun ApplicationCall.respondJsonObj(vararg pairs: Pair<String, Any?>) {
    respondJsonValue(Json.obj(pairs.toList()))
}

//fun RoutingContext.jsonBody() = Json.parse(body().ifEmpty { "{}" }).asObject() ?: throw BadRequestResponse("Empty body")
