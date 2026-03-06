package dev.botta.trantor.web.application.requestmapper

import com.google.gson.JsonParseException
import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.web.application.requestmapper.transformers.*
import io.javalin.http.Context
import kotlin.reflect.KClass

class ApplicationRequestMapper(private val serializer: JsonSerializer) {
    private val transformers = mutableListOf(
        QuerystringApplicationRequestMapperJsonTransformer(),
        PathParamApplicationRequestMapperJsonTransformer(),
    )

    fun addRequestJsonTransformer(transformer: ApplicationRequestMapperJsonTransformer) {
        if (transformers.contains(transformer)) return
        transformers.add(transformer)
    }

    fun <T: Request<*>> toRequest(requestType: KClass<T>, context: Context): T {
        val json = extractJson(requestType, context)
        try {
            return serializer.deserialize(json, requestType.java)
        } catch (e: Throwable) {
            throw JsonParseException(e.message, e)
        }
    }

    fun addResponse(context: Context, response: Any?, statusCode: Int = 200) {
        context.contentType("application/json")
        context.status(statusCode)
        if (response != null) context.result(serializer.serialize(response))
    }

    private fun <T: Request<*>> extractJson(requestType: KClass<T>, context: Context): String {
        var body = context.body()
        if (body.isEmpty()) body = "{}"
        val json = Json.parse(body).asObject() ?: JsonObject()

        transformers.forEach { it.transform(requestType, context, json) }

        return json.toString()
    }
}
