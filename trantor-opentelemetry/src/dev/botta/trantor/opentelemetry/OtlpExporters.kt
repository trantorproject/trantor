package dev.botta.trantor.opentelemetry

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

/** Builds the OTLP exporters the settings describe, one for each signal. */
internal object OtlpExporters {
    fun spanExporter(settings: OpenTelemetrySettings): SpanExporter = when (settings.protocol) {
        OtlpProtocols.HTTP_PROTOBUF -> OtlpHttpSpanExporter.builder()
            .setEndpoint(endpointOf(settings, "traces"))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        OtlpProtocols.GRPC -> OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpointOf(settings, "traces"))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        else -> unknown(settings)
    }

    fun metricExporter(settings: OpenTelemetrySettings): MetricExporter = when (settings.protocol) {
        OtlpProtocols.HTTP_PROTOBUF -> OtlpHttpMetricExporter.builder()
            .setEndpoint(endpointOf(settings, "metrics"))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        OtlpProtocols.GRPC -> OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpointOf(settings, "metrics"))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        else -> unknown(settings)
    }

    private fun unknown(settings: OpenTelemetrySettings): Nothing = throw IllegalArgumentException(
        "OpenTelemetry cannot export over '${settings.protocol}', only over ${OtlpProtocols.all.joinToString()}",
    )

    /**
     * Over HTTP each signal has its own path under the base, as the spec asks of `OTEL_EXPORTER_OTLP_ENDPOINT`,
     * while the exporter wants the whole URL. Over gRPC the base is the address.
     */
    fun endpointOf(settings: OpenTelemetrySettings, signal: String) = when (settings.protocol) {
        OtlpProtocols.GRPC -> settings.endpoint ?: "http://localhost:4317"
        else -> (settings.endpoint ?: "http://localhost:4318").trimEnd('/') + "/v1/$signal"
    }
}
