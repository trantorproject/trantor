package dev.botta.trantor.ai.testing

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/** An OpenTelemetry that keeps every span as it ends. */
class TestTelemetry {
    val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    val openTelemetry: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    val tracer = openTelemetry.getTracer("test")

    /** In the order they ended. */
    val spans: List<SpanData> get() = exporter.finishedSpanItems

    fun named(name: String) = spans.single { it.name == name }
}
