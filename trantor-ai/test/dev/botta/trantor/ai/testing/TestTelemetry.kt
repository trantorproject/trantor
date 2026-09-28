package dev.botta.trantor.ai.testing

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/** An OpenTelemetry that keeps every span as it ends, and every metric until it is asked for. */
class TestTelemetry {
    val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    private val metricReader = InMemoryMetricReader.create()
    val openTelemetry: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        // What an application with trantor-opentelemetry propagates, for what carries the context to another service
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        .build()
    val tracer = openTelemetry.getTracer("test")

    /** In the order they ended. */
    val spans: List<SpanData> get() = exporter.finishedSpanItems

    fun named(name: String) = spans.single { it.name == name }

    val metrics: Collection<MetricData> get() = metricReader.collectAllMetrics()

    fun metric(name: String) = metrics.single { it.name == name }
}
