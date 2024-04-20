package dev.botta.trantor.webApi.httpCQDispatcher.transformers

import dev.botta.json.values.JsonObject
import dev.botta.trantor.webApi.httpCQDispatcher.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class PathParamRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
        context.pathParamMap().forEach { json?.set(it.key, it.value) }
    }
}
