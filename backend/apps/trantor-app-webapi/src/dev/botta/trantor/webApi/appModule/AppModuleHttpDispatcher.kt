package dev.botta.trantor.webApi.appModule

import com.google.gson.JsonParseException
import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.trantor.appServices.AppModule
import dev.botta.trantor.appServices.auth.SystemIdentity
import dev.botta.trantor.core.Event
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.webApi.appModule.transformers.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class AppModuleHttpDispatcher(private val appModule: AppModule, private val serializer: JsonSerializer) {
    private val transformers = mutableListOf(
        QuerystringRequestToJsonTransformer(),
        PathParamRequestToJsonTransformer(),
        SearchQueryRequestToJsonTransformer(),
    )

    suspend inline fun <reified T: Request<*>> execute(ctx: RoutingContext, status: HttpStatusCode = HttpStatusCode.OK) {
        execute(T::class, ctx, status)
    }

    suspend fun notify(event: Event) {
        appModule.notify(event)
    }

    suspend fun <T: Request<*>> execute(actionClass: KClass<T>, ctx: RoutingContext, status: HttpStatusCode = HttpStatusCode.OK) {
        val action = ctx.deserializedBody(actionClass)
        val actionResponse = execute(action, ctx)
        ctx.serialized(actionResponse, status)
    }

    suspend fun <T: Request<R>, R> executeWithoutReturning(actionClass: KClass<T>, ctx: RoutingContext, status: HttpStatusCode = HttpStatusCode.OK): R {
        val action = ctx.deserializedBody(actionClass)
        return execute(action, ctx)
    }

    suspend fun <R> execute(action: Request<R>, ctx: RoutingContext, executionContext: ExecutionContext = ExecutionContext()) =
        appModule.execute(action, executionContext.with("routing_context", ctx))

    suspend fun <R> executeAsSystem(action: Request<R>, ctx: RoutingContext, executionContext: ExecutionContext = ExecutionContext()) =
        appModule.execute(action, executionContext.withIdentity(SystemIdentity()).with("routing_context", ctx))

    suspend fun <T: Any> RoutingContext.deserializedBody(type: KClass<T>): T {
        val json = jsonWithRequestParameters(type)
        try {
            return serializer.deserialize(json, type.java)
        } catch (e: Throwable) {
            throw JsonParseException(e.message, e)
        }
    }

    private suspend fun RoutingContext.jsonWithRequestParameters(type: KClass<*>): String {
        var body = call.receiveText()
        if (body.isEmpty()) body = "{}"
        val json = Json.parse(body).asObject()

        transformers.forEach { it.transform(this, json, type) }

        return json.toString()
    }

    fun addRequestToJsonTransformer(transformer: RequestToJsonTransformer) {
        if (transformers.contains(transformer)) return
        transformers.add(transformer)
    }

    suspend fun RoutingContext.serialized(obj: Any?, status: HttpStatusCode = HttpStatusCode.OK) {
        val text = if (obj != null) serializer.serialize(obj) else ""
        call.respondText(text, ContentType.Application.Json, status)
    }
}
