package dev.botta.trantor.core.queues

/**
 * What travels on a [MessageQueue]: a [type] that says how to read the [body], and what the work it carries needs
 * to go on where it was left. [cid] is the correlation id of the logs, and [traceContext] the trace, as the W3C
 * headers the OpenTelemetry propagator writes (`traceparent`, and `tracestate` or `baggage` when there are). A
 * message enqueued before it had one reads back with an empty one.
 */
data class Message(
    val type: String,
    val body: String,
    val cid: String? = null,
    val traceContext: Map<String, String> = emptyMap(),
)
