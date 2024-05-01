package dev.botta.trantor.webApi.appModule

import com.google.gson.JsonParseException
import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.trantor.appServices.AppModule
import dev.botta.trantor.core.Event
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.webApi.appModule.transformers.*
import io.javalin.http.Context
import kotlin.reflect.KClass

class AppModuleHttpDispatcher(private val appModule: AppModule, private val serializer: JsonSerializer) {
    private val transformers = mutableListOf(
        QuerystringRequestToJsonTransformer(),
        PathParamRequestToJsonTransformer(),
        SearchQueryRequestToJsonTransformer(),
    )

    inline fun <reified T: Request<*>> execute(ctx: Context, statusCode: Int = 200) {
        execute(T::class, ctx, statusCode)
    }

    fun notify(event: Event) { appModule.notify(event) }

    fun <T: Request<*>> execute(actionClass: KClass<T>, ctx: Context, statusCode: Int = 200) {
        val action = ctx.deserializedBody(actionClass)
        val actionResponse = execute(action, ctx)
        ctx.serialized(actionResponse, statusCode)
    }

    private fun <R> execute(action: Request<R>, ctx: Context) = appModule.execute(action, ExecutionContext().with(ctx))

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
        if (transformers.contains(transformer)) return
        transformers.add(transformer)
    }

    fun Context.serialized(obj: Any?, statusCode: Int = 200) {
        contentType("application/json")
        status(statusCode)
        if (obj != null) result(serializer.serialize(obj))
    }
}
