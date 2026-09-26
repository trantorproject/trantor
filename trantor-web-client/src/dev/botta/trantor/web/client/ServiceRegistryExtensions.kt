package dev.botta.trantor.web.client

import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient
import dev.botta.trantor.web.client.tracing.traced
import io.opentelemetry.api.OpenTelemetry

/**
 * Registers the [HttpClient] of the application, one for everybody, so they share its connections. It is OkHttp,
 * the implementation that also streams, and when there is an `OpenTelemetry` in the container every call is traced,
 * whether it was registered before or after.
 *
 * A client with a [key] has its own settings, in `httpClient.<key>`, for the calls that need other timeouts, and
 * is resolved with `services.get<HttpClient>(key)`:
 *
 * ```kotlin
 * services.addHttpClient("ai") { settings, _ -> settings.requestTimeout = 0 }
 * ```
 *
 * A client registered before, with the same key, is kept.
 */
fun ServiceRegistry.addHttpClient(
    key: String? = null,
    configuration: ServiceConfiguration<HttpClientSettings> = { _, _ -> },
) = apply {
    if (!has<HttpClientSettings>(key)) addConfig<HttpClientSettings>(if (key != null) "$SECTION.$key" else SECTION, key)
    configure(HttpClientSettings::class.java, key, configuration)

    if (has<HttpClient>(key)) return@apply

    addSingleton<HttpClient>(key) { services ->
        val client = OkHttpHttpClient(services.get<HttpClientSettings>(key).toOkHttpConfig())
        if (services.has<OpenTelemetry>()) client.traced(services.get<OpenTelemetry>()) else client
    }
}

fun ServiceRegistry.addHttpClient(configuration: ServiceConfiguration<HttpClientSettings>) =
    addHttpClient(null, configuration)

private const val SECTION = "httpClient"
