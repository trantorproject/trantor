package dev.botta.trantor.web.application.requestmapper.transformers

import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.json.values.*
import io.javalin.http.Context
import kotlin.reflect.KClass

class QuerystringApplicationRequestMapperJsonTransformer: ApplicationRequestMapperJsonTransformer {
    override fun <T: Request<*>> transform(requestType: KClass<T>, context: Context, json: JsonObject) {
        context.queryParamMap().forEach {
            if (it.key.endsWith("[]")) {
                json[it.key.removeSuffix("[]")] = Json.array(it.value.map { v -> this.normalizeValue(v) })
            } else if (it.key.contains(".")) {
                val parts = it.key.split(".")
                if (parts.size != 2) {
                    json[it.key] = this.normalizeValue(it.value.firstOrNull())
                    return@forEach
                }
                val obj = json[parts[0]]?.asObject() ?: Json.obj()
                obj[parts[1]] = this.normalizeValue(it.value.firstOrNull())
                json[parts[0]] = obj
            } else {
                json[it.key] = this.normalizeValue(it.value.firstOrNull())
            }
        }
    }

    private fun normalizeValue(value: String?): JsonValue {
        if (value == "null") return Json.NULL
        return Json.value(value)
    }
}
