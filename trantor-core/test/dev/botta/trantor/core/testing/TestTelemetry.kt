package dev.botta.trantor.core.testing

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.TimeUnit.SECONDS

/** An OpenTelemetry that keeps every span as it ends, and propagates W3C trace context as the real one does. */
class TestTelemetry {
    val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    val openTelemetry: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    val tracer = openTelemetry.getTracer("test")

    val spans: List<SpanData> get() = exporter.finishedSpanItems

    fun single(kind: SpanKind) = spans.single { it.kind == kind }

    /** Waits for a span of [kind] to end: a worker ends it on its own thread, after the test saw the work done. */
    fun await(kind: SpanKind): SpanData {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (true) {
            spans.singleOrNull { it.kind == kind }?.let { return it }
            check(System.nanoTime() < deadline) { "No $kind span ended: $spans" }
            Thread.sleep(10)
        }
    }
}
