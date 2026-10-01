package dev.botta.trantor.opentelemetry

import dev.botta.trantor.di.ServiceConfiguration
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.HostEnvironment
import dev.botta.trantor.hosting.HostLifetime
import dev.botta.trantor.primitives.logging.getLogger
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.time.Duration

/**
 * Registers an [OpenTelemetry] that sends the spans and the metrics of the application over OTLP, to a collector or
 * straight to a backend. Only the endpoint and the headers change from one backend to another:
 *
 * ```kotlin
 * services.addOpenTelemetry { settings, _ -> settings.endpoint = "https://otlp.example.com" }
 * ```
 *
 * The SDK is built the first time something asks for it, and the parts of Trantor that trace ask for it when they
 * are created. Spans wait in a batch and leave every few seconds, and metrics every
 * [OpenTelemetrySettings.metricExportInterval]; the rest leaves when the host stops, after every hosted service
 * stopped, so the last requests and jobs are not lost.
 *
 * An [OpenTelemetry] registered before is kept: that is how an application uses the OpenTelemetry Java agent, by
 * registering `GlobalOpenTelemetry.get()`. A [SpanExporter] or a [MetricExporter] registered before replaces the
 * OTLP one.
 */
fun ServiceRegistry.addOpenTelemetry(configuration: ServiceConfiguration<OpenTelemetrySettings> = { _, _ -> }) =
    apply {
        if (!has<OpenTelemetrySettings>()) addConfig<OpenTelemetrySettings>(SECTION)
        configure(configuration)

        if (has<OpenTelemetry>()) return@apply

        addSingletonIfMissing<SpanExporter> { OtlpExporters.spanExporter(it.get<OpenTelemetrySettings>()) }
        addSingletonIfMissing<MetricExporter> { OtlpExporters.metricExporter(it.get<OpenTelemetrySettings>()) }
        addSingleton<OpenTelemetry> { createOpenTelemetry(it) }
    }

private fun createOpenTelemetry(services: ServiceProvider): OpenTelemetry {
    val settings = services.get<OpenTelemetrySettings>()
    if (!settings.enabled) return OpenTelemetry.noop()

    val resource = resourceOf(settings, services.get<HostEnvironment>())

    val sdk = OpenTelemetrySdk.builder()
        // The default of OTEL_PROPAGATORS. The builder has none, and without them the trace stops at every service
        .setPropagators(
            ContextPropagators.create(
                TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(),
                    W3CBaggagePropagator.getInstance(),
                ),
            ),
        )
        .setTracerProvider(
            SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.samplingRatio)))
                .addSpanProcessor(BatchSpanProcessor.builder(services.get<SpanExporter>()).build())
                .build(),
        )
        .setMeterProvider(
            SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(
                    PeriodicMetricReader.builder(services.get<MetricExporter>())
                        .setInterval(Duration.ofMillis(settings.metricExportInterval.toLong()))
                        .build(),
                )
                .build(),
        )
        .build()

    if (settings.registerGlobal) registerGlobal(sdk)
    // After the hosted services, not as one of them: they stop in the reverse order they were registered, and
    // the spans of the last ones to stop would be left behind
    if (services.has<HostLifetime>()) services.get<HostLifetime>().onStopped { sdk.close() }

    return sdk
}

private fun resourceOf(settings: OpenTelemetrySettings, environment: HostEnvironment): Resource {
    val attributes = Attributes.builder()
        .apply { settings.resourceAttributes.forEach { (key, value) -> put(key, value) } }
        .put(stringKey("service.name"), settings.serviceName ?: environment.appName)
        .put(stringKey("deployment.environment.name"), environment.environmentName.lowercase())
        .build()

    return Resource.getDefault().merge(Resource.create(attributes))
}

/**
 * `GlobalOpenTelemetry` can be set once, and reading it before fixes a no-op for good. When somebody got there
 * first, the application keeps working with the one in the container, and the libraries with the other.
 */
private fun registerGlobal(sdk: OpenTelemetrySdk) {
    try {
        GlobalOpenTelemetry.set(sdk)
    } catch (e: IllegalStateException) {
        getLogger("OpenTelemetry").warn(
            "GlobalOpenTelemetry was already set, so the libraries that use it will not send their spans with " +
                "the ones of Trantor. Set registerGlobal to false if that is intended",
        )
    }
}

private const val SECTION = "openTelemetry"
