package dev.botta.trantor.primitives.logging

import io.opentelemetry.api.trace.Span
import org.apache.logging.log4j.core.util.ContextDataProvider

/**
 * Adds the ids of the current span to every log event, as `trace_id` and `span_id`, so a log line can be found
 * from its trace and the other way around. A layout prints them with `%X{trace_id}` and `%X{span_id}`.
 *
 * Log4j finds it through `META-INF/services` and asks it on every event. It reads the span at that moment instead
 * of keeping the ids in the MDC, so it is right for spans opened anywhere, by Trantor, the application or the
 * OpenTelemetry Java agent, and there is nothing to clean up when a span ends. Outside a span, or without an
 * OpenTelemetry SDK, it adds nothing.
 *
 * The keys are the ones of OpenTelemetry's own provider (`opentelemetry-log4j-context-data-2.17-autoconfigure`),
 * which is still alpha. When both are on the classpath they write the same values.
 */
class TraceContextDataProvider: ContextDataProvider {
    override fun supplyContextData(): Map<String, String> {
        val span = Span.current().spanContext
        if (!span.isValid) return emptyMap()

        return mapOf(TRACE_ID to span.traceId, SPAN_ID to span.spanId)
    }

    companion object {
        const val TRACE_ID = "trace_id"
        const val SPAN_ID = "span_id"
    }
}
