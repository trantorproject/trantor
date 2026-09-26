package dev.botta.trantor.opentelemetry

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

/** Builds the OTLP exporter the settings describe. */
internal object OtlpExporters {
    fun create(settings: OpenTelemetrySettings): SpanExporter = when (settings.protocol) {
        OtlpProtocols.HTTP_PROTOBUF -> OtlpHttpSpanExporter.builder()
            .setEndpoint(endpointOf(settings))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        OtlpProtocols.GRPC -> OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpointOf(settings))
            .apply { settings.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        else -> throw IllegalArgumentException(
            "OpenTelemetry cannot export over '${settings.protocol}', only over ${OtlpProtocols.all.joinToString()}",
        )
    }

    /**
     * Over HTTP each signal has its own path under the base, as the spec asks of `OTEL_EXPORTER_OTLP_ENDPOINT`,
     * while the exporter wants the whole URL. Over gRPC the base is the address.
     */
    fun endpointOf(settings: OpenTelemetrySettings) = when (settings.protocol) {
        OtlpProtocols.GRPC -> settings.endpoint ?: "http://localhost:4317"
        else -> (settings.endpoint ?: "http://localhost:4318").trimEnd('/') + "/v1/traces"
    }
}
