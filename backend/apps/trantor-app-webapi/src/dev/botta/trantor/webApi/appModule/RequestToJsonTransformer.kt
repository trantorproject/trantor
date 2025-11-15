package dev.botta.trantor.webApi.appModule

import dev.botta.json.values.JsonObject
import io.ktor.server.routing.*
import kotlin.reflect.KClass

interface RequestToJsonTransformer {
    fun transform(context: RoutingContext, json: JsonObject?, type: KClass<*>)
}
