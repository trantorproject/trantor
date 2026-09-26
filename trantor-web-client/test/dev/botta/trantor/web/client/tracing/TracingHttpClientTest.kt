@file:Suppress("ClassName")

package dev.botta.trantor.web.client.tracing

import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient
import dev.botta.trantor.web.client.testing.RecordingHttpClient
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

class TracingHttpClientTest {
    @Nested
    inner class `a call` {
        @Test
        fun `is a client span named after its method`() {
            client.get("https://api.example.com:8443/v1/orders/7?expand=items")

            val span = exporter.finishedSpanItems.single()
            assertThat(span.name).isEqualTo("GET")
            assertThat(span.kind).isEqualTo(SpanKind.CLIENT)
            assertThat(span.attributes[stringKey("http.request.method")]).isEqualTo("GET")
            assertThat(span.attributes[stringKey("url.full")])
                .isEqualTo("https://api.example.com:8443/v1/orders/7?expand=items")
            assertThat(span.attributes[stringKey("server.address")]).isEqualTo("api.example.com")
            assertThat(span.attributes[longKey("server.port")]).isEqualTo(8443)
            assertThat(span.attributes[longKey("http.response.status_code")]).isEqualTo(200)
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `to the default port of its scheme says which one it is`() {
            client.post("https://api.example.com/v1/orders", "{}")

            val span = exporter.finishedSpanItems.single()
            assertThat(span.name).isEqualTo("POST")
            assertThat(span.attributes[longKey("server.port")]).isEqualTo(443)
        }

        @Test
        fun `is current while it is sent, so the logs of the client carry it`() {
            client.get("https://api.example.com/v1/orders")

            assertThat(backend.currentSpan).isEqualTo(exporter.finishedSpanItems.single().spanContext)
        }

        @Test
        fun `hangs from the span that was current`() {
            val request = tracer.spanBuilder("GET /checkout").startSpan()

            request.makeCurrent().use { client.get("https://api.example.com/v1/orders") }

            val call = exporter.finishedSpanItems.single()
            assertThat(call.parentSpanId).isEqualTo(request.spanContext.spanId)
            assertThat(call.traceId).isEqualTo(request.spanContext.traceId)
        }

        @Test
        fun `does not show the credentials nor the signatures of its url`() {
            client.get("https://nico:s3cret@files.example.com/a.pdf?Signature=abc&v=1")

            assertThat(exporter.finishedSpanItems.single().attributes[stringKey("url.full")])
                .isEqualTo("https://REDACTED:REDACTED@files.example.com/a.pdf?Signature=REDACTED&v=1")
        }
    }

    @Nested
    inner class `the trace` {
        @Test
        fun `travels to the server in the traceparent header`() {
            client.get("https://api.example.com/v1/orders")

            val span = exporter.finishedSpanItems.single().spanContext
            assertThat(backend.requests.single().headers["traceparent"])
                .isEqualTo("00-${span.traceId}-${span.spanId}-${span.traceFlags.asHex()}")
        }

        @Test
        fun `is added to a copy, and the request of the caller stays as it was`() {
            val request = HttpRequest("https://api.example.com/v1/orders", null, mapOf("Accept" to "application/json"))

            client.get(request)

            assertThat(request.headers).containsOnlyKeys("Accept")
            assertThat(backend.requests.single().headers).containsKeys("Accept", "traceparent")
        }

        @Test
        fun `reaches a real server through OkHttp`() {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(200).body("{}").build())

                OkHttpHttpClient().traced(openTelemetry).get(server.url("/v1/orders").toString())

                val span = exporter.finishedSpanItems.single().spanContext
                assertThat(server.takeRequest().headers["traceparent"]).contains(span.traceId, span.spanId)
            }
        }
    }

    @Nested
    inner class `an error` {
        @Test
        fun `of the client, a 4xx, fails the span with its status`() {
            backend.status = 404

            client.get("https://api.example.com/v1/orders/7")

            val span = exporter.finishedSpanItems.single()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("404")
        }

        @Test
        fun `of the server, a 5xx, fails the span with its status`() {
            backend.status = 503

            client.get("https://api.example.com/v1/orders/7")

            val span = exporter.finishedSpanItems.single()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("503")
        }

        @Test
        fun `that never got an answer fails the span with the cause, and is thrown as it was`() {
            val timeout = HttpClientError("timeout", SocketTimeoutException("Read timed out"))
            backend.error = timeout

            assertThatThrownBy { client.get("https://api.example.com/v1/orders/7") }.isSameAs(timeout)

            val span = exporter.finishedSpanItems.single()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("java.net.SocketTimeoutException")
            assertThat(span.attributes[longKey("http.response.status_code")]).isNull()
            assertThat(span.events.map { it.name }).containsExactly("exception")
        }
    }

    @Nested
    inner class `a stream` {
        @Test
        fun `ends its span once the headers arrive, as the other clients do`() {
            val response = client.stream(HttpMethods.Post, HttpRequest("https://api.example.com/v1/responses", "{}"))

            val span = exporter.finishedSpanItems.single()
            assertThat(span.name).isEqualTo("POST")
            assertThat(span.attributes[longKey("http.response.status_code")]).isEqualTo(200)
            assertThat(response.lines().toList()).containsExactly("data: hola")
            response.close()
        }

        @Test
        fun `that does not open fails the span`() {
            backend.error = HttpClientError("refused", java.net.ConnectException("Connection refused"))

            assertThatThrownBy { client.stream(HttpMethods.Get, HttpRequest("https://api.example.com/events")) }
                .isInstanceOf(HttpClientError::class.java)

            assertThat(exporter.finishedSpanItems.single().attributes[stringKey("error.type")])
                .isEqualTo("java.net.ConnectException")
        }
    }

    private val exporter = InMemorySpanExporter.create()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    private val tracer = openTelemetry.getTracer("test")
    private val backend = RecordingHttpClient()
    private val client = backend.traced(openTelemetry)
}
