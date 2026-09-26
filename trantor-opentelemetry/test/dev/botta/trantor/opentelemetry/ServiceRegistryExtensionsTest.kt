@file:Suppress("ClassName")

package dev.botta.trantor.opentelemetry

import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.Host
import dev.botta.trantor.opentelemetry.testing.RecordingSpanExporter
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceRegistryExtensionsTest {
    @Nested
    inner class `the spans` {
        @Test
        fun `reach the exporter, named after the application and its environment`() {
            val host = started()

            span(host, "GET /invoices")
            host.stop()

            assertThat(exporter.spans.map { it.name }).containsExactly("GET /invoices")
            assertThat(exporter.spans.single().resource.getAttribute(SERVICE_NAME)).isEqualTo("billing")
            assertThat(exporter.spans.single().resource.getAttribute(ENVIRONMENT)).isEqualTo("staging")
        }

        @Test
        fun `wait in a batch, and stopping the host sends what is left`() {
            val host = started()

            span(host, "GET /invoices")

            assertThat(exporter.spans).isEmpty()
            host.stop()
            assertThat(exporter.spans).hasSize(1)
            assertThat(exporter.isShutdown).isTrue()
        }

        @Test
        fun `carry the service name and the resource attributes of the configuration`() {
            val host = started(
                config = mapOf(
                    "openTelemetry.serviceName" to "billing-api",
                    "openTelemetry.resourceAttributes.team" to "payments",
                ),
            )

            span(host, "GET /invoices")
            host.stop()

            val resource = exporter.spans.single().resource
            assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("billing-api")
            assertThat(resource.getAttribute(stringKey("team"))).isEqualTo("payments")
        }

        @Test
        fun `carry what the application set in code`() {
            val host = started(register = { addOpenTelemetry { settings, _ -> settings.serviceName = "billing-worker" } })

            span(host, "process invoices")
            host.stop()

            assertThat(exporter.spans.single().resource.getAttribute(SERVICE_NAME)).isEqualTo("billing-worker")
        }
    }

    @Nested
    inner class `sampling` {
        @Test
        fun `at a ratio of zero sends nothing`() {
            val host = started(config = mapOf("openTelemetry.samplingRatio" to "0.0"))

            span(host, "GET /invoices")
            host.stop()

            assertThat(exporter.spans).isEmpty()
        }

        @Test
        fun `follows a caller that already decided to sample`() {
            val host = started(config = mapOf("openTelemetry.samplingRatio" to "0.0"))
            val caller = Span.wrap(
                SpanContext.createFromRemoteParent(
                    "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(),
                    TraceState.getDefault(),
                ),
            )

            Context.root().with(caller).makeCurrent().use { span(host, "GET /invoices") }
            host.stop()

            assertThat(exporter.spans.single().traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
        }
    }

    @Nested
    inner class `registering it` {
        @Test
        fun `twice is the same as once, and what the second call sets still counts`() {
            val host = started(register = {
                addOpenTelemetry()
                addOpenTelemetry { settings, _ -> settings.serviceName = "billing-api" }
            })

            span(host, "GET /invoices")
            host.stop()

            assertThat(host.services.getAll<OpenTelemetry>()).hasSize(1)
            assertThat(exporter.spans.single().resource.getAttribute(SERVICE_NAME)).isEqualTo("billing-api")
        }

        @Test
        fun `keeps the OpenTelemetry the application registered`() {
            val own = OpenTelemetry.noop()
            val host = started(register = {
                addSingleton<OpenTelemetry>(own)
                addOpenTelemetry()
            })

            assertThat(host.services.get<OpenTelemetry>()).isSameAs(own)
        }

        @Test
        fun `disabled, records nothing`() {
            val host = started(config = mapOf("openTelemetry.enabled" to "false"))

            val sampled = span(host, "GET /invoices")
            host.stop()

            assertThat(sampled).isFalse()
            assertThat(exporter.spans).isEmpty()
        }
    }

    @Nested
    inner class `the global` {
        @Test
        fun `is the one of the container, for the libraries that look for it there`() {
            val host = started()
            host.services.get<OpenTelemetry>()

            GlobalOpenTelemetry.getTracer("some-library").spanBuilder("query").startSpan().end()
            host.stop()

            assertThat(exporter.spans.map { it.name }).containsExactly("query")
        }

        @Test
        fun `is left as it was when somebody set it first`() {
            GlobalOpenTelemetry.set(OpenTelemetry.noop())
            val host = started()

            val sampled = span(host, "GET /invoices")

            assertThat(sampled).isTrue()
            assertThat(GlobalOpenTelemetry.getTracer("some-library").spanBuilder("query").startSpan().spanContext.isValid)
                .isFalse()
        }

        @Test
        fun `is left alone when the settings say so`() {
            val host = started(config = mapOf("openTelemetry.registerGlobal" to "false"))

            host.services.get<OpenTelemetry>()

            assertThat(GlobalOpenTelemetry.isSet()).isFalse()
        }
    }

    @AfterEach
    fun cleanUp() {
        hosts.forEach { runCatching { it.stop() } }
        GlobalOpenTelemetry.resetForTest()
    }

    private fun started(
        config: Map<String, String> = emptyMap(),
        register: ServiceRegistry.() -> Unit = { addOpenTelemetry() },
    ): Host {
        val builder = Host.builder {
            appName = "billing"
            environmentName = "staging"
        }
        builder.config.addMemoryCollection(config)
        builder.services.addSingleton<JsonSerializer>(GsonSerializer())
        builder.services.addSingleton<SpanExporter>(exporter)
        builder.services.register()

        return builder.build().also { hosts.add(it) }.apply { start() }
    }

    /** Opens and ends a span, and says whether it was sampled. */
    private fun span(host: Host, name: String): Boolean {
        val span = host.services.get<OpenTelemetry>().getTracer("test").spanBuilder(name).startSpan()
        span.end()
        return span.spanContext.isSampled
    }

    private val exporter = RecordingSpanExporter()
    private val hosts = mutableListOf<Host>()

    private companion object {
        val SERVICE_NAME = stringKey("service.name")
        val ENVIRONMENT = stringKey("deployment.environment.name")
    }
}
