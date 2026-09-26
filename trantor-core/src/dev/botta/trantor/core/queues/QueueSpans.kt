package dev.botta.trantor.core.queues

import dev.botta.trantor.primitives.TrantorBuildInfo
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

/**
 * The spans of a message, following the messaging semantic conventions (Development): `send {queue}` when it is
 * enqueued and `process {queue}` when it is handled.
 *
 * The context of the send span travels in [Message.traceContext]: a single message needs no `create` span, and the
 * send span is its creation context. The process span is a child of it, and also links to it. The conventions
 * prefer the link alone, and allow the parent for a single message processed outside any other span, which is the
 * case of a worker: that way a request, the job it dispatched and what the job did are one tree. The price is that
 * a retry hours later lands in the same trace.
 */
internal class QueueSpans(openTelemetry: OpenTelemetry) {
    private val tracer = openTelemetry.getTracer(INSTRUMENTATION, TrantorBuildInfo.version)
    private val propagator = openTelemetry.propagators.textMapPropagator

    /** Sends [message] with [enqueue] inside a send span, child of [parent], and with its context in the message. */
    fun send(queue: MessageQueue, message: Message, parent: Context, enqueue: (Message) -> Unit) {
        val span = start("send", SpanKind.PRODUCER, queue, message).setParent(parent).startSpan()

        traced(span) {
            val traceContext = mutableMapOf<String, String>()
            propagator.inject(Context.current(), traceContext) { carrier, key, value -> carrier!![key] = value }
            enqueue(message.copy(traceContext = traceContext))
        }
    }

    /** Runs [handle] inside the process span of [received], current for everything the handler does. */
    fun process(queue: MessageQueue, received: ReceivedMessage, handle: () -> Unit) {
        val creation = Span.fromContext(propagator.extract(Context.root(), received.message.traceContext, MapGetter))
        val builder = start("process", SpanKind.CONSUMER, queue, received.message)
            .setAttribute(stringKey("messaging.message.id"), received.id)

        if (creation.spanContext.isValid) {
            builder.setParent(Context.root().with(creation)).addLink(creation.spanContext)
        } else {
            builder.setNoParent()
        }

        traced(builder.startSpan(), handle)
    }

    private fun start(operation: String, kind: SpanKind, queue: MessageQueue, message: Message): SpanBuilder =
        tracer.spanBuilder("$operation ${queue.name}")
            .setSpanKind(kind)
            .setAttribute(stringKey("messaging.system"), queue.system)
            .setAttribute(stringKey("messaging.destination.name"), queue.name)
            .setAttribute(stringKey("messaging.operation.name"), operation)
            .setAttribute(stringKey("messaging.operation.type"), operation)
            // The conventions have no attribute for the type of a message, and it is what says which job it is
            .setAttribute(stringKey("trantor.message.type"), message.type)

    private fun traced(span: Span, block: () -> Unit) {
        try {
            span.makeCurrent().use { block() }
        } catch (e: Throwable) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute(stringKey("error.type"), e.javaClass.name)
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }

    private object MapGetter: TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>) = carrier.keys

        override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
    }

    private companion object {
        const val INSTRUMENTATION = "dev.botta.trantor.core.queues"
    }
}
