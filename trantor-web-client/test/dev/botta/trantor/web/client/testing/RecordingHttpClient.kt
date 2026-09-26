package dev.botta.trantor.web.client.testing

import dev.botta.trantor.web.client.*
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext

/** Answers every call with [status], or fails with [error], and records what it was sent and which span was current. */
class RecordingHttpClient(var status: Int = 200, var error: Throwable? = null): HttpClient() {
    val requests = mutableListOf<HttpRequest>()
    var currentSpan: SpanContext? = null

    override fun get(request: HttpRequest) = answer(request)

    override fun post(request: HttpRequest) = answer(request)

    override fun put(request: HttpRequest) = answer(request)

    override fun patch(request: HttpRequest) = answer(request)

    override fun delete(request: HttpRequest) = answer(request)

    override fun stream(method: HttpMethods, request: HttpRequest, options: StreamOptions): HttpStreamResponse {
        record(request)
        return object: HttpStreamResponse {
            override val status = this@RecordingHttpClient.status
            override val contentType = "text/event-stream"
            override val headers = emptyMap<String, String>()

            override fun lines() = sequenceOf("data: hola")

            override fun body() = "data: hola"

            override fun cancel() {}

            override fun close() {}
        }
    }

    private fun answer(request: HttpRequest): HttpResponse {
        record(request)
        return HttpResponse(status, "{}".toByteArray(), "application/json")
    }

    private fun record(request: HttpRequest) {
        requests.add(request)
        currentSpan = Span.current().spanContext
        error?.let { throw it }
    }
}
