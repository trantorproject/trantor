package dev.botta.trantor.restApi

import dev.botta.trantor.web.server.HttpServer

class HttpApp(private val config: Config) {
    private val httpServer = HttpServer(config.server)

    fun start() {
        httpServer.start()
    }

    fun stop() {
        httpServer.stop()
    }

    data class Config(
        val server: HttpServer.Config = HttpServer.Config(),
    )
}
