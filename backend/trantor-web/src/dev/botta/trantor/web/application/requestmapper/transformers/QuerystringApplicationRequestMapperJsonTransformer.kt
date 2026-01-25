package dev.botta.trantor.web.application.requestmapper.transformers

import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.web.application.requestmapper.transformers.ApplicationRequestMapperJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class QuerystringApplicationRequestMapperJsonTransformer: ApplicationRequestMapperJsonTransformer {
    override fun <T: Request<*>> transform(requestType: KClass<T>, context: Context, json: JsonObject) {
        context.queryParamMap().forEach {
            if (it.key.endsWith("[]")) {
                json[it.key.removeSuffix("[]")] = Json.array(it.value)
            } else {
                json[it.key] = it.value.firstOrNull()
            }
        }
    }
}
