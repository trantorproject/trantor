package dev.botta.trantor.web.application.routes

import dev.botta.cqbus.requests.Request
import dev.botta.trantor.web.application.requestmapper.ApplicationRequestMapper
import dev.botta.trantor.web.server.RouteRegister
import io.javalin.http.*
import io.javalin.websocket.WsConfig
import java.util.function.Consumer
import kotlin.reflect.KClass

/**
 * The routes of a [dev.botta.trantor.web.application.WebApplication], which can be declared as the use case they run:
 * `post<PlaceOrder>("/orders")`. Its [mapper] and its [executor] are what a way of exposing use cases of another
 * module builds on, as an MCP endpoint does.
 */
class ApplicationRouteRegister(
    private val routes: RouteRegister,
    /** Builds a request out of an HTTP call, with the serializer of the application. */
    val mapper: ApplicationRequestMapper,
    /** Runs a request through the middlewares of the application, with the `Context` of the call. */
    val executor: WebApplicationExecutor,
): RouteRegister {
    override val openTelemetry get() = routes.openTelemetry

    override fun before(handler: Handler) = apply {
        routes.before(handler)
    }

    override fun beforeMatched(handler: Handler) = apply {
        routes.beforeMatched(handler)
    }

    override fun after(handler: Handler) = apply {
        routes.after(handler)
    }

    override fun afterMatched(handler: Handler) = apply {
        routes.afterMatched(handler)
    }

    override fun post(path: String, handler: Handler) = apply {
        routes.post(path, handler)
    }

    fun <T: Request<*>> post(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
        routes.post(path) { handleRequest(it, requestType, statusCode) }
    }

    inline fun <reified T: Request<*>> post(path: String, statusCode: Int = 200) = apply {
        post(path, T::class, statusCode)
    }

    override fun get(path: String, handler: Handler) = apply {
        routes.get(path, handler)
    }

    fun <T: Request<*>> get(path: String, requestType: KClass<T>) = apply {
        routes.get(path) { handleRequest(it, requestType) }
    }

    inline fun <reified T: Request<*>> get(path: String) = apply {
        get(path, T::class)
    }

    override fun put(path: String, handler: Handler) = apply {
        routes.put(path, handler)
    }

    fun <T: Request<*>> put(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
        routes.put(path) { handleRequest(it, requestType, statusCode) }
    }

    inline fun <reified T: Request<*>> put(path: String, statusCode: Int = 200) = apply {
        put(path, T::class, statusCode)
    }

    override fun patch(path: String, handler: Handler) = apply {
        routes.patch(path, handler)
    }

    fun <T: Request<*>> patch(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
        routes.patch(path) { handleRequest(it, requestType, statusCode) }
    }

    inline fun <reified T: Request<*>> patch(path: String, statusCode: Int = 200) = apply {
        patch(path, T::class, statusCode)
    }

    override fun delete(path: String, handler: Handler) = apply {
        routes.delete(path, handler)
    }

    fun <T: Request<*>> delete(path: String, requestType: KClass<T>, statusCode: Int = 200) = apply {
        routes.delete(path) { handleRequest(it, requestType, statusCode) }
    }

    inline fun <reified T: Request<*>> delete(path: String, statusCode: Int = 200) = apply {
        delete(path, T::class, statusCode)
    }

    private fun <T: Request<*>> handleRequest(context: Context, requestType: KClass<T>, statusCode: Int = 200) {
        val request = mapper.toRequest(requestType, context) as Request<*>
        val response = executor.execute(request, context)
        mapper.addResponse(context, response, statusCode)
    }

    override fun ws(path: String, consumer: Consumer<WsConfig>) = apply {
        routes.ws(path, consumer)
    }

    override fun wsBefore(consumer: Consumer<WsConfig>) = apply {
        routes.wsBefore(consumer)
    }

    override fun wsAfter(consumer: Consumer<WsConfig>) = apply {
        routes.wsAfter(consumer)
    }
}
