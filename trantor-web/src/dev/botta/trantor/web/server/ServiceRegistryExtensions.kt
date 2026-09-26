package dev.botta.trantor.web.server

import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService
import io.opentelemetry.api.OpenTelemetry

/**
 * Registers the [HttpServer] as a hosted service, with its settings read from the `httpServer` section. It traces
 * with the `OpenTelemetry` of the container when there is one, whether it was registered before or after.
 */
fun ServiceRegistry.addHttpServer() = apply {
    if (has<HttpServerSettings>()) return@apply

    addConfig<HttpServerSettings>("httpServer")
    addSingleton {
        HttpServer(
            it.getOrDefault<HttpServerSettings> { HttpServerSettings() },
            it.getOrDefault<OpenTelemetry> { OpenTelemetry.noop() },
        )
    }
    addHostedService { it.get<HttpServer>() }
}
