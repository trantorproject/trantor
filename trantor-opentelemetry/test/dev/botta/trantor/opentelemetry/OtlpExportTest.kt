package dev.botta.trantor.opentelemetry

import com.sun.net.httpserver.HttpServer
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.hosting.Host
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The spans and the metrics leave the application over OTLP/HTTP, as a collector receives them. A local server stands
 * in for the collector; it answers with an empty body, which is a valid empty response of either.
 */
class OtlpExportTest {
    @Test
    fun `stopping the host posts the spans to the collector, with the headers of the settings`() {
        val host = Host.builder { appName = "billing" }
        host.config.addMemoryCollection(
            "openTelemetry.endpoint" to "http://localhost:${collector.address.port}",
            "openTelemetry.headers.x-api-key" to "abc123",
        )
        host.services.addSingleton<JsonSerializer>(GsonSerializer())
        host.services.addOpenTelemetry()
        val started = host.build().apply { start() }

        started.services.get<OpenTelemetry>().getTracer("test").spanBuilder("GET /invoices").startSpan().end()
        started.stop()

        val request = received.single { it.path == "/v1/traces" }
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/traces")
        assertThat(request.contentType).isEqualTo("application/x-protobuf")
        assertThat(request.apiKey).isEqualTo("abc123")
        assertThat(String(request.body)).contains("GET /invoices", "billing")
    }

    @Test
    fun `and the metrics, to their own path under the same base`() {
        val host = Host.builder { appName = "billing" }
        host.config.addMemoryCollection("openTelemetry.endpoint" to "http://localhost:${collector.address.port}")
        host.services.addSingleton<JsonSerializer>(GsonSerializer())
        host.services.addOpenTelemetry()
        val started = host.build().apply { start() }

        started.services.get<OpenTelemetry>().getMeter("test").counterBuilder("invoices.created").build().add(1)
        started.stop()

        val request = received.single { it.path == "/v1/metrics" }
        assertThat(request.contentType).isEqualTo("application/x-protobuf")
        assertThat(String(request.body)).contains("invoices.created", "billing")
    }

    @AfterEach
    fun cleanUp() {
        collector.stop(0)
        GlobalOpenTelemetry.resetForTest()
    }

    private val received = CopyOnWriteArrayList<Received>()
    private val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            received.add(
                Received(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestHeaders.getFirst("x-api-key"),
                    exchange.requestBody.readAllBytes(),
                ),
            )
            exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    private class Received(
        val method: String,
        val path: String,
        val contentType: String?,
        val apiKey: String?,
        val body: ByteArray,
    )
}
