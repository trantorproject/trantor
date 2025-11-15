package dev.botta.trantor.webApi.appModule.transformers

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.webApi.appModule.RequestToJsonTransformer
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class QuerystringRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: RoutingContext, json: JsonObject?, type: KClass<*>) {
        context.call.queryParameters.entries().forEach {
            if (it.key.endsWith("[]")) {
                json?.set(it.key.removeSuffix("[]"), Json.array(it.value))
            } else {
                json?.set(it.key, it.value.firstOrNull())
            }
        }
    }
}
