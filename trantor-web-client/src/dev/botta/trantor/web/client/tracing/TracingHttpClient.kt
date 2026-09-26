package dev.botta.trantor.web.client.tracing

import dev.botta.trantor.primitives.TrantorBuildInfo
import dev.botta.trantor.primitives.telemetry.UrlRedaction
import dev.botta.trantor.web.client.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import java.net.URI

/**
 * Wraps an [HttpClient] so that every call is a `CLIENT` span, following the HTTP semantic conventions (Stable),
 * and carries the trace to the server in the `traceparent` header. It works the same over any client, which is why
 * it wraps instead of living in each one, as the OpenTelemetry instrumentation of OkHttp does.
 *
 * The span ends when the response headers arrive, also for [stream]: that is what .NET and the OpenTelemetry
 * instrumentations measure, the wait for an answer. Reading the body is the time of whoever reads it, and a
 * generation that streams for a minute is measured by its own span.
 */
class TracingHttpClient(private val delegate: HttpClient, openTelemetry: OpenTelemetry): HttpClient() {
    private val tracer = openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version)
    private val propagator = openTelemetry.propagators.textMapPropagator

    override fun get(request: HttpRequest) = call(HttpMethods.Get, request, delegate::get)

    override fun post(request: HttpRequest) = call(HttpMethods.Post, request, delegate::post)

    override fun put(request: HttpRequest) = call(HttpMethods.Put, request, delegate::put)

    override fun patch(request: HttpRequest) = call(HttpMethods.Patch, request, delegate::patch)

    override fun delete(request: HttpRequest) = call(HttpMethods.Delete, request, delegate::delete)

    override fun stream(method: HttpMethods, request: HttpRequest, options: StreamOptions): HttpStreamResponse =
        traced(method, request, { it.status }) { delegate.stream(method, it, options) }

    private fun call(method: HttpMethods, request: HttpRequest, send: (HttpRequest) -> HttpResponse) =
        traced(method, request, { it.status }, send)

    private fun <T> traced(
        method: HttpMethods,
        request: HttpRequest,
        statusOf: (T) -> Int,
        send: (HttpRequest) -> T,
    ): T {
        val span = start(method, request)

        val result = try {
            span.makeCurrent().use { send(withTraceparent(request)) }
        } catch (e: Throwable) {
            // The wrapper says nothing about what went wrong, what it wraps does
            val cause = if (e is HttpClientError) e.cause ?: e else e
            fail(span, cause.javaClass.name)
            span.recordException(e)
            span.end()
            throw e
        }

        val status = statusOf(result)
        span.setAttribute(longKey("http.response.status_code"), status.toLong())
        // Unlike a server, a client failed when it was told no
        if (status >= 400) fail(span, status.toString())
        span.end()
        return result
    }

    private fun start(method: HttpMethods, request: HttpRequest): Span {
        val builder = tracer.spanBuilder(method.value)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(stringKey("http.request.method"), method.value)
            .setAttribute(stringKey("url.full"), UrlRedaction.url(request.url))

        val uri = runCatching { URI(request.url) }.getOrNull()
        uri?.host?.let { builder.setAttribute(stringKey("server.address"), it) }
        uri?.let { portOf(it) }?.let { builder.setAttribute(longKey("server.port"), it.toLong()) }

        return builder.startSpan()
    }

    private fun portOf(uri: URI) = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> null
    }

    /** A copy with the header, so the request of the caller can be sent again, in another trace, as it was. */
    private fun withTraceparent(request: HttpRequest): HttpRequest {
        val headers = request.headers.toMutableMap()
        propagator.inject(Context.current(), headers) { carrier, key, value -> carrier!![key] = value }
        return HttpRequest(request.url, request.body, headers)
    }

    private fun fail(span: Span, type: String) {
        span.setStatus(StatusCode.ERROR)
        span.setAttribute(stringKey("error.type"), type)
    }

    private companion object {
        const val INSTRUMENTATION = "dev.botta.trantor.web.client"
    }
}

/** This client, with every call traced by [openTelemetry]. */
fun HttpClient.traced(openTelemetry: OpenTelemetry): HttpClient = TracingHttpClient(this, openTelemetry)
