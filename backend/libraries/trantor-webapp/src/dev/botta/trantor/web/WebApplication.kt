package dev.botta.trantor.web

import com.google.gson.JsonParseException
import dev.botta.json.parser.JsonParseError
import dev.botta.lang.DetailsExt
import dev.botta.trantor.app.auth.*
import dev.botta.trantor.config.Config
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.hosting.*
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.web.errorHandlers.*
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller

class WebApplication(private val host: Host): Host, RouteRegistrant {
    override val services: ServiceProvider
        get() = host.services
    override val config: Config
        get() = host.config
    override val environment: HostEnvironment
        get() = host.environment
    val httpServer = services.get<HttpServer>()
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

    override fun start() {
        host.start()
    }

    override fun stop(timeoutSeconds: Int) {
        host.stop(timeoutSeconds)
    }

    companion object {
        fun builder(args: Array<String>) = WebApplicationBuilder(WebApplicationBuilderConfig(args = args))

        fun builder(config: WebApplicationBuilderConfig) = WebApplicationBuilder(config)

        fun builder(details: DetailsExt<WebApplicationBuilderConfig> = {}) = WebApplicationBuilder(WebApplicationBuilderConfig().apply(details))
    }
}
