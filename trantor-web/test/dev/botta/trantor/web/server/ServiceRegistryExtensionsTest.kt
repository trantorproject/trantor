package dev.botta.trantor.web.server

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS

@Tag("slow")
class ServiceRegistryExtensionsTest {
    @Test
    fun `the server traces with the OpenTelemetry of the container`() {
        val exporter = InMemorySpanExporter.create()
        val tracing = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        registry.addSingleton<OpenTelemetry>(OpenTelemetrySdk.builder().setTracerProvider(tracing).build())
        registry.addHttpServer()
        val server = started()

        RestAssured.given().get("http://localhost:$port/ping").then().statusCode(200)

        awaitUntil { exporter.finishedSpanItems.any { it.kind == SpanKind.SERVER } }
        assertThat(exporter.finishedSpanItems.single().name).isEqualTo("GET /ping")
        server.stop(5)
    }

    @Test
    fun `and without one it serves all the same`() {
        registry.addHttpServer()
        val server = started()

        RestAssured.given().get("http://localhost:$port/ping").then().statusCode(200)

        server.stop(5)
    }

    @AfterEach
    fun cleanUp() {
        RestAssured.reset()
    }

    private fun started(): HttpServer {
        val server = DefaultServiceProvider(registry).get<HttpServer>()
        server.get("/ping") { it.result("pong") }
        server.start()
        return server
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition was not met in time" }
            Thread.sleep(10)
        }
    }

    private val port = ServerSocket(0).use { it.localPort }
    private val registry = ServiceRegistry(ConfigManager().addMemoryCollection("httpServer.port" to "$port"))
        .apply { addSingleton<JsonSerializer>(GsonSerializer()) }
}
