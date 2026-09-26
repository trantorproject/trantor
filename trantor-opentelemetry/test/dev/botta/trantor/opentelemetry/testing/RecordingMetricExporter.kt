package dev.botta.trantor.opentelemetry.testing

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps every metric it is given, also after it is shut down, like [RecordingSpanExporter] does with spans. */
class RecordingMetricExporter: MetricExporter {
    val metrics = CopyOnWriteArrayList<MetricData>()

    @Volatile
    var isShutdown = false
        private set

    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        this.metrics.addAll(metrics)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown = true
        return CompletableResultCode.ofSuccess()
    }

    override fun getAggregationTemporality(instrumentType: InstrumentType) = AggregationTemporality.CUMULATIVE
}
