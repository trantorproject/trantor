package dev.botta.trantor.opentelemetry.testing

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps every span it is given, also after it is shut down. The `InMemorySpanExporter` of the SDK forgets them on
 * shutdown, which is the moment the tests look at.
 */
class RecordingSpanExporter: SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    @Volatile
    var isShutdown = false
        private set

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown = true
        return CompletableResultCode.ofSuccess()
    }
}
