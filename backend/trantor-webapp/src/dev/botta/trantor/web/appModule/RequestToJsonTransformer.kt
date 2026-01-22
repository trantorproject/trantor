package dev.botta.trantor.web.appModule

import dev.botta.json.values.JsonObject
import io.javalin.http.Context
import kotlin.reflect.KClass

interface RequestToJsonTransformer {
    fun transform(context: Context, json: JsonObject?, type: KClass<*>)
}
