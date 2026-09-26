package dev.botta.trantor.web.server.tracing

import dev.botta.trantor.primitives.TrantorBuildInfo
import dev.botta.trantor.primitives.telemetry.UrlRedaction
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.Context as OtelContext
import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A `SERVER` span around every request, following the HTTP semantic conventions (Stable): it goes on from the
 * `traceparent` the caller sent, it is current while Javalin handles the request, so what the handlers do hangs
 * from it, and it is named `{method} {route}` once the route is known.
 *
 * It is a servlet filter, not a Javalin handler, because it has to be current around all of Javalin, error
 * handlers included, and has to end after the response is written. For an asynchronous request (`ctx.future`)
 * that is when the async context completes, not when the filter returns.
 */
internal class ServerSpanFilter(openTelemetry: OpenTelemetry): Filter {
    private val tracer = openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version)
    private val propagator = openTelemetry.propagators.textMapPropagator

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as HttpServletRequest
        val res = response as HttpServletResponse
        val parent = propagator.extract(OtelContext.current(), req, RequestHeaders)
        val span = startSpan(req, parent)
        val traced = TracedRequest(req) { end(span, req, res) }
        req.setAttribute(ServerSpans.SPAN, span)

        try {
            span.makeCurrent().use { chain.doFilter(traced, res) }
        } catch (e: Throwable) {
            // Javalin answers every exception itself, so this is Jetty or a filter failing
            fail(span, e.javaClass.name, e)
            traced.end()
            throw e
        }
        if (!traced.isAsync) traced.end()
    }

    private fun startSpan(req: HttpServletRequest, parent: OtelContext): Span {
        val method = req.method.takeIf { it in ServerSpans.KNOWN_METHODS }
        val builder = tracer.spanBuilder(method ?: "HTTP")
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(stringKey("http.request.method"), method ?: "_OTHER")
            .setAttribute(stringKey("url.path"), req.requestURI)
            .setAttribute(stringKey("url.scheme"), req.scheme)
            .setAttribute(stringKey("server.address"), req.serverName)
            .setAttribute(longKey("server.port"), req.serverPort.toLong())
            .setAttribute(stringKey("client.address"), req.remoteAddr)
            .setAttribute(stringKey("network.protocol.version"), req.protocol.substringAfter("HTTP/"))

        if (method == null) builder.setAttribute(stringKey("http.request.method_original"), req.method)
        req.queryString?.let { builder.setAttribute(stringKey("url.query"), UrlRedaction.query(it)) }
        req.getHeader("User-Agent")?.let { builder.setAttribute(stringKey("user_agent.original"), it) }

        return builder.startSpan()
    }

    /**
     * A 4xx is the client's mistake and leaves the span as it is; a 5xx fails it, as the conventions ask of a
     * server. The type of the error is the exception an error handler took, or the status when none did.
     */
    private fun end(span: Span, req: HttpServletRequest, res: HttpServletResponse) {
        val status = res.status
        val exception = req.getAttribute(ServerSpans.EXCEPTION) as? Throwable

        span.setAttribute(longKey("http.response.status_code"), status.toLong())
        if (status >= 500) fail(span, exception?.javaClass?.name ?: status.toString(), exception)
        span.end()
    }

    private fun fail(span: Span, type: String, exception: Throwable?) {
        span.setStatus(StatusCode.ERROR)
        span.setAttribute(stringKey("error.type"), type)
        exception?.let { span.recordException(it) }
    }

    /**
     * Learns when the request goes asynchronous, and ends the span when the async context completes. The listener
     * is added as the async context starts, before anything can complete it.
     */
    private class TracedRequest(request: HttpServletRequest, private val onEnd: () -> Unit):
        HttpServletRequestWrapper(request) {
        private val ended = AtomicBoolean(false)

        @Volatile
        var isAsync = false
            private set

        override fun startAsync(): AsyncContext = listen(super.startAsync())

        override fun startAsync(request: ServletRequest, response: ServletResponse): AsyncContext =
            listen(super.startAsync(request, response))

        fun end() {
            if (ended.compareAndSet(false, true)) onEnd()
        }

        private fun listen(context: AsyncContext) = context.also {
            isAsync = true
            it.addListener(object: AsyncListener {
                override fun onComplete(event: AsyncEvent) = end()

                override fun onTimeout(event: AsyncEvent) {}

                override fun onError(event: AsyncEvent) {}

                override fun onStartAsync(event: AsyncEvent) {}
            })
        }
    }

    private object RequestHeaders: TextMapGetter<HttpServletRequest> {
        override fun keys(carrier: HttpServletRequest) = carrier.headerNames.toList()

        override fun get(carrier: HttpServletRequest?, key: String): String? = carrier?.getHeader(key)
    }

    private companion object {
        const val INSTRUMENTATION = "dev.botta.trantor.web"
    }
}

/** What [dev.botta.trantor.web.server.HttpServer] tells the span of a request while Javalin handles it. */
internal object ServerSpans {
    const val SPAN = "trantor.otel.span"
    const val EXCEPTION = "trantor.otel.exception"

    /** RFC 9110, PATCH and QUERY, as the conventions define the known methods. */
    val KNOWN_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH", "QUERY")

    /**
     * Javalin knows the route only once it matched it, and says so in `endpointHandlerPath`. When nothing matched it
     * puts a sentence there instead of a path, or, when the request never left the `BEFORE` stage, it throws. Then
     * the span keeps the name of the method alone: the path is never a name, it would give one name per order, per
     * user, per anything.
     */
    fun routed(ctx: Context) {
        val span = ctx.attribute<Span>(SPAN) ?: return
        if (ctx.handlerType() == HandlerType.BEFORE) return
        val route = ctx.endpointHandlerPath().takeIf { it.startsWith("/") } ?: return
        val method = ctx.req().method

        span.setAttribute(stringKey("http.route"), route)
        if (method in KNOWN_METHODS) span.updateName("$method $route")
    }

    /** An error handler took [error]. Whether it fails the span depends on the status the handler answers. */
    fun handled(ctx: Context, error: Throwable) {
        ctx.attribute(EXCEPTION, error)
    }
}
