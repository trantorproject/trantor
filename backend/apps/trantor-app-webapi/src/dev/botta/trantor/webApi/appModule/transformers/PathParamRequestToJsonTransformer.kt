package dev.botta.trantor.webApi.appModule.transformers

import dev.botta.json.values.JsonObject
import dev.botta.trantor.webApi.appModule.RequestToJsonTransformer
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class PathParamRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: RoutingContext, json: JsonObject?, type: KClass<*>) {
        context.call.parameters.entries().forEach { json?.set(it.key, it.value.firstOrNull()) }
    }
}
