package dev.botta.trantor.webApi.httpCQDispatcher

import com.google.gson.JsonParseException
import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.trantor.appServices.CQDispatcher
import dev.botta.trantor.core.Event
import dev.botta.trantor.core.serialization.Serializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.webApi.httpCQDispatcher.transformers.*
import io.javalin.http.Context
import kotlin.reflect.KClass

class HttpCQDispatcher(
    private val dispatcher: CQDispatcher,
    private val serializer: Serializer = GsonSerializer(),
) {
    private val transformers = mutableListOf(
        QuerystringRequestToJsonTransformer(),
        PathParamRequestToJsonTransformer(),
        SearchQueryRequestToJsonTransformer(),
    )

    inline fun <reified T: Request<*>> execute(ctx: Context, statusCode: Int = 200) {
        execute(T::class, ctx, statusCode)
    }

    fun notify(event: Event) { dispatcher.notify(event) }

    fun <T: Request<*>> execute(actionClass: KClass<T>, ctx: Context, statusCode: Int = 200) {
        val action = ctx.deserializedBody(actionClass)
        val actionResponse = execute(action, ctx)
        ctx.serialized(actionResponse, statusCode)
    }

    fun <R> execute(action: Request<R>, ctx: Context) = dispatcher.execute(action, ExecutionContext().with(ctx))

    fun <T: Any> Context.deserializedBody(type: KClass<T>): T {
        val json = jsonWithRequestParameters(type)
        try {
            return serializer.deserialize(json, type.java)
        } catch (e: Throwable) {
            throw JsonParseException(e.message, e)
        }
    }

    private fun Context.jsonWithRequestParameters(type: KClass<*>): String {
        var body = body()
        if (body.isEmpty()) body = "{}"
        val json = Json.parse(body).asObject()

        transformers.forEach { it.transform(this, json, type) }

        return json.toString()
    }

    fun addRequestToJsonTransformer(transformer: RequestToJsonTransformer) {
        transformers.add(transformer)
    }

    fun Context.serialized(obj: Any?, statusCode: Int = 200) {
        contentType("application/json")
        status(statusCode)
        if (obj != null) result(serializer.serialize(obj))
    }
}
