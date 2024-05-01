package dev.botta.trantor.webApi

import com.google.gson.JsonParseException
import dev.botta.cqbus.CQBus
import dev.botta.json.parser.JsonParseError
import dev.botta.trantor.appServices.AppModule
import dev.botta.trantor.appServices.auth.*
import dev.botta.trantor.config.Config
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.eventBus.EventBus
import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.webApi.errorHandlers.*
import dev.botta.trantor.webApi.httpCQDispatcher.*

abstract class BaseWebApi(registry: ServiceRegistry): RouteRegistrant {
    val services = DefaultServiceProvider(registry)
    val config = services.get<Config>()
    val environment = services.get<AppEnvironment>()
    val modules = mutableListOf<AppModule>()
    protected val httpServer = HttpServer(services.getOrDefault<HttpServerConfig> { HttpServerConfig() })
    protected val cqBus = services.get<CQBus>()
    protected val eventBus = services.get<EventBus>()
    protected val httpCQDispatcher = services.get<HttpCQDispatcher>()
    override val routes get() = httpServer.routes

    init {
        addKnownExceptions()
        addRegisteredRequestToJsonTransformers()
        addRegisteredInterceptors()
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

    private fun addRegisteredRequestToJsonTransformers() {
        services.getAll<RequestToJsonTransformer>().forEach { addRequestToJsonTransformer(it) }
    }

    private fun addRegisteredInterceptors() {
        services.getAll<HttpRequestInterceptor>().forEach { addInterceptor(it) }
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

    fun addControllers(vararg controllers: Controller) {
        httpServer.addControllers(*controllers)
    }

    fun addController(factory: (httpCQDispatcher: HttpCQDispatcher) -> Controller) {
        addController(factory(httpCQDispatcher))
    }

    fun addRequestToJsonTransformer(transformer: RequestToJsonTransformer) {
        httpCQDispatcher.addRequestToJsonTransformer(transformer)
    }

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }
}
