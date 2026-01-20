package dev.botta.trantor.web.appModule.transformers

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.web.appModule.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass

class QuerystringRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
        context.queryParamMap().forEach {
            if (it.key.endsWith("[]")) {
                json?.set(it.key.removeSuffix("[]"), Json.array(it.value))
            } else {
                json?.set(it.key, it.value.firstOrNull())
            }
        }
    }
}
