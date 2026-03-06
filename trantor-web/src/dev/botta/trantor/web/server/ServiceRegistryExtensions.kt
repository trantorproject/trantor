package dev.botta.trantor.web.server

import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addHttpServer() = apply {
    if (has<HttpServerSettings>()) return@apply

    addConfig<HttpServerSettings>("httpServer")
    addSingleton { HttpServer(it.getOrDefault<HttpServerSettings> { HttpServerSettings() }) }
    addHostedService { it.get<HttpServer>() }
}
