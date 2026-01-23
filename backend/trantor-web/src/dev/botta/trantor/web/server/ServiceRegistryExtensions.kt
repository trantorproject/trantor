package dev.botta.trantor.web.server

import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addHttpServer() = apply {
    if (has<HttpServerConfig>()) return@apply

    addConfig<HttpServerConfig>("httpServer")
    addSingleton { HttpServer(it.getOrDefault<HttpServerConfig> { HttpServerConfig() }) }
    addHostedService { it.get<HttpServer>() }
}
