@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import dev.botta.trantor.web.errorHandlers.InternalErrorHandler
import dev.botta.trantor.web.errorHandlers.NotFoundErrorHandler
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpServerTracingTest {
    @Nested
    inner class `a request` {
        @Test
        fun `is a server span named after its route, not after its path`() {
            RestAssured.given().header("User-Agent", "tests").get("/orders/order-7").then().statusCode(200)

            val span = serverSpan()
            assertThat(span.name).isEqualTo("GET /orders/{id}")
            assertThat(span.kind).isEqualTo(SpanKind.SERVER)
            assertThat(span.attributes[stringKey("http.request.method")]).isEqualTo("GET")
            assertThat(span.attributes[stringKey("http.route")]).isEqualTo("/orders/{id}")
            assertThat(span.attributes[stringKey("url.path")]).isEqualTo("/orders/order-7")
            assertThat(span.attributes[stringKey("url.scheme")]).isEqualTo("http")
            assertThat(span.attributes[longKey("http.response.status_code")]).isEqualTo(200)
            assertThat(span.attributes[longKey("server.port")]).isEqualTo(port.toLong())
            assertThat(span.attributes[stringKey("user_agent.original")]).isEqualTo("tests")
            assertThat(span.attributes[stringKey("network.protocol.version")]).isEqualTo("1.1")
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `says who sent it by their address, without the brackets an IPv6 one has in a URL`() {
            val request = HttpRequest.newBuilder(URI("http://[::1]:$port/orders/order-7")).build()

            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())

            assertThat(serverSpan().attributes[stringKey("client.address")]).isEqualTo("0:0:0:0:0:0:0:1")
        }

        @Test
        fun `that matches no route is named after its method alone`() {
            RestAssured.given().get("/nothing-here").then().statusCode(404)

            val span = serverSpan()
            assertThat(span.name).isEqualTo("GET")
            assertThat(span.attributes[stringKey("http.route")]).isNull()
            assertThat(span.attributes[longKey("http.response.status_code")]).isEqualTo(404)
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        }

        @Test
        fun `with a method nobody knows is an HTTP span of another method`() {
            RestAssured.given().request("PURGE", "/orders/order-7")

            val span = serverSpan()
            assertThat(span.name).isEqualTo("HTTP")
            assertThat(span.attributes[stringKey("http.request.method")]).isEqualTo("_OTHER")
            assertThat(span.attributes[stringKey("http.request.method_original")]).isEqualTo("PURGE")
        }

        @Test
        fun `keeps its query, without the signatures in it`() {
            RestAssured.given().get("/search?q=libros&sig=secret&page=2").then().statusCode(200)

            assertThat(serverSpan().attributes[stringKey("url.query")]).isEqualTo("q=libros&sig=REDACTED&page=2")
        }
    }

    @Nested
    inner class `an error` {
        @Test
        fun `that an error handler turns into a 4xx is the client's, and the span is not failed`() {
            RestAssured.given().get("/orders/missing/load").then().statusCode(404)

            val span = serverSpan()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
            assertThat(span.attributes[stringKey("error.type")]).isNull()
            assertThat(span.events).isEmpty()
        }

        @Test
        fun `that an error handler turns into a 5xx fails the span, naming the exception`() {
            RestAssured.given().get("/crash").then().statusCode(500)

            val span = serverSpan()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo(Crash::class.java.name)
            assertThat(span.events.map { it.name }).containsExactly("exception")
        }

        @Test
        fun `that no error handler took fails the span with its status`() {
            RestAssured.given().get("/boom").then().statusCode(500)

            val span = serverSpan()
            assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(span.attributes[stringKey("error.type")]).isEqualTo("500")
        }
    }

    @Nested
    inner class `the trace` {
        @Test
        fun `goes on from the one the caller sent`() {
            RestAssured.given()
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .get("/orders/order-7").then().statusCode(200)

            val span = serverSpan()
            assertThat(span.traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
            assertThat(span.parentSpanId).isEqualTo("00f067aa0ba902b7")
        }

        @Test
        fun `is current in the handler, so what it does hangs from the request`() {
            RestAssured.given().get("/current").then().statusCode(200)

            val request = serverSpan()
            val child = exporter.finishedSpanItems.single { it.name == "load order" }
            assertThat(child.parentSpanId).isEqualTo(request.spanId)
            assertThat(child.traceId).isEqualTo(request.traceId)
        }

        @Test
        fun `of an asynchronous request ends when its future does, not when the handler returns`() {
            RestAssured.given().get("/later").then().statusCode(200)

            val span = serverSpan()
            assertThat((span.endEpochNanos - span.startEpochNanos) / 1_000_000).isGreaterThanOrEqualTo(300)
            assertThat(span.name).isEqualTo("GET /later")
            assertThat(span.attributes[longKey("http.response.status_code")]).isEqualTo(200)
        }
    }

    @Nested
    inner class `a websocket` {
        @Test
        fun `still connects through the span of its upgrade`() {
            val echoed = CompletableFuture<String>()
            val socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI("ws://localhost:$port/echo"), object: WebSocket.Listener {
                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        echoed.complete(data.toString())
                        return null
                    }
                })
                .get(5, SECONDS)

            socket.sendText("hola", true).get(5, SECONDS)

            assertThat(echoed.get(5, SECONDS)).isEqualTo("hola")
            socket.abort()
        }
    }

    @Test
    fun `its routes trace with its OpenTelemetry, for what builds on them and traces on its own`() {
        assertThat(server.routes.openTelemetry).isSameAs(openTelemetry)
    }

    @BeforeEach
    fun forgetTheSpansOfOtherTests() {
        exporter.reset()
    }

    @BeforeAll
    fun startTheServer() {
        port = freePort()
        server = HttpServer(HttpServerSettings(port = port), openTelemetry)

        server.get("/orders/{id}") { it.jsonObj("id" to it.pathParam("id")) }
        server.get("/orders/{id}/load") { throw OrderNotFound("No such order") }
        server.get("/search") { it.jsonObj("query" to it.queryParam("q")) }
        server.get("/crash") { throw Crash("The disk is full") }
        server.get("/boom") { error("something nobody planned for") }
        server.get("/current") {
            openTelemetry.getTracer("test").spanBuilder("load order").startSpan().end()
            it.jsonObj("span" to Span.current().spanContext.spanId)
        }
        server.get("/later") {
            it.future {
                CompletableFuture.supplyAsync({ "done" }, CompletableFuture.delayedExecutor(300, MILLISECONDS))
                    .thenAccept { result -> it.result(result) }
            }
        }
        server.ws("/echo") { ws -> ws.onMessage { it.send(it.message()) } }
        server.addErrorHandler(NotFoundErrorHandler(OrderNotFound::class))
        server.addErrorHandler(InternalErrorHandler(Crash::class))

        server.start()
        RestAssured.baseURI = "http://localhost:$port"
    }

    @AfterAll
    fun stopTheServer() {
        server.stop(10)
        RestAssured.reset()
    }

    /**
     * The span of the request. It ends on the server after the response is written, which can be after the client
     * has read it.
     */
    private fun serverSpan(): SpanData {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (true) {
            exporter.finishedSpanItems.singleOrNull { it.kind == SpanKind.SERVER }?.let { return it }
            check(System.nanoTime() < deadline) { "No server span arrived: ${exporter.finishedSpanItems}" }
            Thread.sleep(10)
        }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class OrderNotFound(message: String): Exception(message)

    private class Crash(message: String): Exception(message)

    private val exporter = InMemorySpanExporter.create()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    private var port = 0
    private lateinit var server: HttpServer
}
