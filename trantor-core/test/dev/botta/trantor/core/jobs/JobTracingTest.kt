package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.jobs.serialization.DefaultJobSerializer
import dev.botta.trantor.core.testing.TestTelemetry
import dev.botta.trantor.core.testing.WaitingQueue
import dev.botta.trantor.core.tx.NullTransactionManager
import dev.botta.trantor.serialization.gson.GsonSerializer
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

/** A request that dispatches a job, and the job, in one trace: what a trace viewer shows as one tree. */
class JobTracingTest {
    @Test
    fun `the job runs in the trace of the request that dispatched it`() {
        val traceOfTheJob = LinkedBlockingQueue<String>()
        dispatcher.registerHandler(
            SendEmail::class,
            JobHandler { traceOfTheJob.add(Span.current().spanContext.traceId) },
        )
        processor.start()
        val request = telemetry.tracer.spanBuilder("POST /signups").startSpan()

        request.makeCurrent().use { dispatcher.dispatch(SendEmail("nico@example.com")) }
        request.end()

        val send = telemetry.single(SpanKind.PRODUCER)
        val process = telemetry.await(SpanKind.CONSUMER)
        assertThat(traceOfTheJob.poll(5, SECONDS)).isEqualTo(request.spanContext.traceId)
        assertThat(send.parentSpanId).isEqualTo(request.spanContext.spanId)
        assertThat(process.parentSpanId).isEqualTo(send.spanId)
    }

    @AfterEach
    fun stopTheProcessor() {
        processor.stop(2)
    }

    class SendEmail(val to: String = ""): Job()

    private fun <T: Job> JobHandler(execute: (T) -> Unit) = object: JobHandler<T> {
        override fun execute(job: T) = execute(job)
    }

    private val telemetry = TestTelemetry()
    private val queue = WaitingQueue("emails")
    private val queues = JobQueueRegistry().apply { addQueue("emails", queue) }
    private val handlers = JobHandlerRegistry()
    private val serializer = DefaultJobSerializer(GsonSerializer())
    private val dispatcher = DefaultJobDispatcher(
        queues, handlers, serializer, NullTransactionManager(), openTelemetry = telemetry.openTelemetry,
    )
    private val processor = JobProcessor(handlers, serializer, queue, openTelemetry = telemetry.openTelemetry)
}
