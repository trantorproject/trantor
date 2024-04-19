package dev.botta.trantor.restApi.httpCQDispatcher.transformers

import dev.botta.json.values.JsonObject
import dev.botta.trantor.restApi.httpCQDispatcher.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class QuerystringRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
        context.queryParamMap().forEach { json?.set(it.key, it.value.firstOrNull()) }
    }
}
