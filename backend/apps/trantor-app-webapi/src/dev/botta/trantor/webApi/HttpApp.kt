package dev.botta.trantor.webApi

import dev.botta.trantor.appServices.CQEDispatcher
import dev.botta.trantor.core.serialization.Serializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.webApi.auth.SessionTokenAuthenticationMiddleware
import dev.botta.trantor.webApi.httpCQDispatcher.HttpCQDispatcher

class HttpApp(private val config: Config) {
    private val httpServer = HttpServer(config.server)
    val httpCQDispatcher = HttpCQDispatcher(config.requestDispatcher, config.jsonSerializer)

    init {
        config.requestDispatcher.registerMiddleware(SessionTokenAuthenticationMiddleware())
    }

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        httpServer.addInterceptor(interceptor)
    }

    fun registerController(controllerFactory: (httpCQDispatcher: HttpCQDispatcher) -> Controller) {
        httpServer.addControllers(controllerFactory(httpCQDispatcher))
    }

    fun registerController(controller: Controller) {
        httpServer.addControllers(controller)
    }

    fun registerControllers(vararg controllers: Controller) {
        httpServer.addControllers(*controllers)
    }

    data class Config(
        val requestDispatcher: CQEDispatcher,
        val server: HttpServerConfig = HttpServerConfig(),
        val jsonSerializer: Serializer = GsonSerializer(),
    )
}
