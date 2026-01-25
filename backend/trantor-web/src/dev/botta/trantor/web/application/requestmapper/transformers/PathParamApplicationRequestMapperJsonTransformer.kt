package dev.botta.trantor.web.application.requestmapper.transformers

import dev.botta.cqbus.requests.Request
import dev.botta.json.values.JsonObject
import dev.botta.trantor.web.application.requestmapper.transformers.ApplicationRequestMapperJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class PathParamApplicationRequestMapperJsonTransformer: ApplicationRequestMapperJsonTransformer {
    override fun <T: Request<*>> transform(requestType: KClass<T>, context: Context, json: JsonObject) {
        context.pathParamMap().forEach { json[it.key] = it.value }
    }
}
