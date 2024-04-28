package dev.botta.trantor.webApi

import com.google.gson.JsonParseException
import dev.botta.json.parser.JsonParseError
import dev.botta.trantor.appServices.auth.*
import dev.botta.trantor.config.Config
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.webApi.errorHandlers.*

abstract class BaseWebApi(val config: Config, registry: ServiceRegistry): RouteRegistrant {
    val services = DefaultServiceProvider(registry)
    val environment get() = services.get<AppEnvironment>()
    protected val httpServer = HttpServer(services.getOrDefault<HttpServerConfig> { HttpServerConfig() })
    override val routes get() = httpServer.routes

    init {
        addKnownExceptions()
    }

    private fun addKnownExceptions() {
        addNotAuthenticatedError<NotAuthenticatedError>()
        addForbiddenError<UnauthorizedAccessError>()
        addNotFoundError<NotFoundError>()
        addBadRequestError<DomainError>()
        addBadRequestError<JsonParseError>()
        addBadRequestError<JsonParseException>()
        addInternalError<Exception>()
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        httpServer.addInterceptor(interceptor)
    }

    fun <T: Exception> addErrorHandler(handler: BaseJsonErrorHandler<T>) {
        httpServer.addErrorHandler(handler)
    }

    fun addController(controller: Controller) {
        httpServer.addControllers(controller)
    }

    fun registerControllers(vararg controllers: Controller) {
        httpServer.addControllers(*controllers)
    }

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }
}
