package dev.botta.trantor.web.application.requestmapper.transformers

import dev.botta.cqbus.requests.Request
import dev.botta.json.values.JsonObject
import io.javalin.http.Context
import kotlin.reflect.KClass

interface ApplicationRequestMapperJsonTransformer {
    fun <T: Request<*>> transform(requestType: KClass<T>, context: Context, json: JsonObject)
}
