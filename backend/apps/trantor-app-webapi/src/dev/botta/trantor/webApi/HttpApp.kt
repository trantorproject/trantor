package dev.botta.trantor.webApi

import com.google.gson.JsonParseException
import dev.botta.json.parser.JsonParseError
import dev.botta.trantor.appServices.CQDispatcher
import dev.botta.trantor.appServices.auth.*
import dev.botta.trantor.core.serialization.Serializer
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.webApi.auth.SessionTokenAuthenticationMiddleware
import dev.botta.trantor.webApi.httpCQDispatcher.HttpCQDispatcher
import io.javalin.http.Context
import org.slf4j.Logger

class HttpApp(private val config: Config) {
    private val httpServer = HttpServer(config.server)
    val httpCQDispatcher = HttpCQDispatcher(config.requestDispatcher, config.jsonSerializer)

    init {
        handleKnownExceptions()
        config.requestDispatcher.registerMiddleware(SessionTokenAuthenticationMiddleware())
    }

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }

    private fun handleKnownExceptions() {
        registerException<NotAuthenticatedError>(::notAuthenticatedErrorHandler)
        registerException<UnauthorizedAccessError>(::forbiddenErrorHandler)
        registerException<NotFoundError>(::notFoundErrorHandler)
        registerException<DomainError>(::badRequestJsonErrorHandler)
        registerException<JsonParseError>(::badRequestJsonErrorHandler)
        registerException<JsonParseException>(::badRequestJsonErrorHandler)
        registerException<Exception>(::internalServerErrorHandler)
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        httpServer.addInterceptor(interceptor)
    }

    fun <T: Exception> registerException(clazz: Class<T>, handler: HttpErrorHandler<T>) {
        httpServer.registerException(clazz, handler)
    }

    inline fun <reified T: Exception> registerException(handler: HttpErrorHandler<T>) {
        registerException(T::class.java, handler)
    }

    fun registerController(controllerFactory: (httpCQDispatcher: HttpCQDispatcher) -> Controller) {
        httpServer.registerControllers(controllerFactory(httpCQDispatcher))
    }

    fun registerController(controller: Controller) {
        httpServer.registerControllers(controller)
    }

    fun registerControllers(vararg controllers: Controller) {
        httpServer.registerControllers(*controllers)
    }

    fun <T: Exception> badRequestJsonErrorHandler(e: T, ctx: Context, logger: Logger) = errorHandler<T>(400).handle(e, ctx, logger)

    fun <T: Exception> notAuthenticatedErrorHandler(e: T, ctx: Context, logger: Logger) = errorHandler<T>(401).handle(e, ctx, logger)

    fun <T: Exception> forbiddenErrorHandler(e: T, ctx: Context, logger: Logger) = errorHandler<T>(403).handle(e, ctx, logger)

    fun <T: Exception> notFoundErrorHandler(e: T, ctx: Context, logger: Logger) = errorHandler<T>(404).handle(e, ctx, logger)

    fun <T: Exception> errorHandler(status: Int): HttpErrorHandler<T> {
        return HttpErrorHandler { e, ctx, logger ->
            ctx.status(status)
            logger.info(e.message, e)
            ctx.jsonError(e)
        }
    }

    fun <T: Exception> internalServerErrorHandler(e: T, ctx: Context, logger: Logger) {
        logger.error("Uncaught exception ${e.javaClass.simpleName}: ${e.message}", e)
        ctx.status(500)
        ctx.jsonError("Exception", "Internal error")
    }

    data class Config(
        val requestDispatcher: CQDispatcher,
        val server: HttpServer.Config = HttpServer.Config(),
        val jsonSerializer: Serializer = GsonSerializer(),
    )
}
