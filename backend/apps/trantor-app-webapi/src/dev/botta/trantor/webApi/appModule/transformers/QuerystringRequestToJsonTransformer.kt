package dev.botta.trantor.webApi.appModule.transformers

import dev.botta.json.values.JsonObject
import dev.botta.trantor.webApi.appModule.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class QuerystringRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
        context.queryParamMap().forEach { json?.set(it.key, it.value.firstOrNull()) }
    }
}
