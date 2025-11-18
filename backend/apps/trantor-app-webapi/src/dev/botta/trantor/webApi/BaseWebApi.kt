package dev.botta.trantor.webApi

import com.google.gson.JsonParseException
import dev.botta.json.parser.JsonParseError
import dev.botta.trantor.appServices.auth.*
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.serviceProvider.ServiceProvider
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.webApi.errorHandlers.*

abstract class BaseWebApi(val services: ServiceProvider): RouteRegistrant {
    val config = services.config
    val environment = services.get<AppEnvironment>()
    protected val httpServer = services.get<HttpServer>()
    override val routes get() = httpServer.routes

    init {
        addKnownExceptions()
    }

    private fun addKnownExceptions() {
        addNotAuthenticatedError<NotAuthenticatedError>()
        addForbiddenError<UnauthorizedAccessError>()
        addForbiddenError<ForbiddenError>()
        addNotFoundError<NotFoundError>()
        addBadRequestError<DomainError>()
        addBadRequestError<JsonParseError>()
        addBadRequestError<JsonParseException>()
        addInternalError<Exception>()
    }

    fun <T: Exception> addErrorHandler(handler: BaseJsonErrorHandler<T>) {
        httpServer.addErrorHandler(handler)
    }

    fun addController(controller: Controller) {
        httpServer.addControllers(controller)
    }

    fun addControllers(vararg controllers: Controller) {
        httpServer.addControllers(*controllers)
    }

    fun start() {
        onBeforeStart()
        httpServer.start()
        onStart()
    }

    fun stop() {
        onBeforeStop()
        httpServer.stop()
        onStop()
    }

    protected open fun onBeforeStart() {}
    protected open fun onStart() {}
    protected open fun onBeforeStop() {}
    protected open fun onStop() {}
}
