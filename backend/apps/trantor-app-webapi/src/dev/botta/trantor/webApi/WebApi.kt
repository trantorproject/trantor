package dev.botta.trantor.webApi

import dev.botta.trantor.config.Config
import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller

class WebApi(val config: Config, val services: ServiceProvider): RouteRegistrant {
    private val httpServer = HttpServer(services.getOrDefault<HttpServerConfig> { HttpServerConfig() })
    val environment get() = services.get<AppEnvironment>()
    override val routes get() = httpServer.routes

    init {
        registerKnownExceptions()
    }

    private fun registerKnownExceptions() {
//        registerException<NotAuthenticatedError>(::notAuthenticatedErrorHandler)
//        registerException<UnauthorizedAccessError>(::forbiddenErrorHandler)
//        registerException<NotFoundError>(::notFoundErrorHandler)
//        registerException<DomainError>(::badRequestJsonErrorHandler)
//        registerException<JsonParseError>(::badRequestJsonErrorHandler)
//        registerException<JsonParseException>(::badRequestJsonErrorHandler)
//        registerException<Exception>(::internalServerErrorHandler)
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

    fun registerController(controller: Controller) {
        httpServer.registerControllers(controller)
    }

    fun registerControllers(vararg controllers: Controller) {
        httpServer.registerControllers(*controllers)
    }

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }

    companion object {
        fun createBuilder(appName: String? = null) = WebApiBuilder(appName)
    }
}
