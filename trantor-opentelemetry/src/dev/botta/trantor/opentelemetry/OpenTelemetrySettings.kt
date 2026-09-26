package dev.botta.trantor.opentelemetry

import dev.botta.env.Env
import java.net.URLDecoder

/**
 * Read from the `openTelemetry` section, and changed from `addOpenTelemetry { settings, services -> ... }`.
 *
 * Every default comes from the standard `OTEL_*` variable when it is set, so an application configured for
 * OpenTelemetry elsewhere keeps working here. The configuration wins over the variable.
 */
data class OpenTelemetrySettings(
    /** `false` registers the no-op `OpenTelemetry`: spans are opened and cost nothing. `OTEL_SDK_DISABLED`. */
    var enabled: Boolean = Env["OTEL_SDK_DISABLED"]?.lowercase() != "true",
    /** The `service.name` of every span. `null` is the `appName` of the host. `OTEL_SERVICE_NAME`. */
    var serviceName: String? = Env["OTEL_SERVICE_NAME"],
    /**
     * The base URL of the collector or the backend, as `OTEL_EXPORTER_OTLP_ENDPOINT`: over HTTP the spans go to
     * `v1/traces` under it. `null` is a collector on this machine, `http://localhost:4318` over HTTP and
     * `http://localhost:4317` over gRPC.
     */
    var endpoint: String? = Env["OTEL_EXPORTER_OTLP_ENDPOINT"],
    /** One of [OtlpProtocols]. `OTEL_EXPORTER_OTLP_PROTOCOL`. */
    var protocol: String = Env["OTEL_EXPORTER_OTLP_PROTOCOL"] ?: OtlpProtocols.HTTP_PROTOBUF,
    /** Sent with every export, which is where most backends take their key. `OTEL_EXPORTER_OTLP_HEADERS`. */
    var headers: Map<String, String> = parseKeyValues(Env["OTEL_EXPORTER_OTLP_HEADERS"]),
    /** Added to the resource, next to the service name and the environment. `OTEL_RESOURCE_ATTRIBUTES`. */
    var resourceAttributes: Map<String, String> = parseKeyValues(Env["OTEL_RESOURCE_ATTRIBUTES"]),
    /**
     * The fraction of the traces that start here to keep, from 0 to 1. A trace that arrives from a caller keeps
     * the decision of the caller, so a trace is never cut in half. `OTEL_TRACES_SAMPLER` is not read: its eight
     * forms do not fit in one setting.
     */
    var samplingRatio: Double = 1.0,
    /**
     * Also makes it the `GlobalOpenTelemetry`, for the libraries that look for it there. Trantor itself never
     * does: it takes the one in the container.
     */
    var registerGlobal: Boolean = true,
)

/** The OTLP transports the exporter speaks. */
object OtlpProtocols {
    const val HTTP_PROTOBUF = "http/protobuf"
    const val GRPC = "grpc"

    val all = listOf(HTTP_PROTOBUF, GRPC)
}

/**
 * Reads a list of pairs in the form of `OTEL_EXPORTER_OTLP_HEADERS` and `OTEL_RESOURCE_ATTRIBUTES`:
 * `key1=value1,key2=value2`, with the values percent-encoded as in W3C Baggage.
 */
internal fun parseKeyValues(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()

    return value.split(",")
        .map { it.split("=", limit = 2) }
        .filter { it.size == 2 && it[0].isNotBlank() }
        .associate { (key, raw) -> key.trim() to URLDecoder.decode(raw.trim(), Charsets.UTF_8) }
}
