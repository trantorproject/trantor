@file:Suppress("ClassName")

package dev.botta.trantor.core.queues

import dev.botta.trantor.core.testing.FakeReceivedMessage
import dev.botta.trantor.core.testing.TestTelemetry
import dev.botta.trantor.core.testing.WaitingQueue
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class MessageQueueProcessorTest {
    @Nested
    inner class `what comes off the queue` {
        @Test
        fun `is handed to whoever is processing it`() {
            val handled = CountDownLatch(1)
            processorFor { handled.countDown() }.start()

            queue.arrive(messageOf("SendEmail"))

            assertThat(handled.await(5, SECONDS)).isTrue()
        }

        @Test
        fun `is deleted once it was processed, so nobody sees it twice`() {
            val handled = CountDownLatch(1)
            processorFor { handled.countDown() }.start()

            queue.arrive(messageOf("SendEmail"))
            handled.await(5, SECONDS)

            assertThat(queue.awaitDeleted()).isEqualTo(1)
        }

        @Test
        fun `several messages in one poll all get processed`() {
            val handled = CountDownLatch(3)
            processorFor { handled.countDown() }.start()

            queue.arrive(messageOf("a"), messageOf("b"), messageOf("c"))

            assertThat(handled.await(5, SECONDS)).isTrue()
        }
    }

    @Nested
    inner class `a message that fails` {
        @Test
        fun `is left on the queue, so it comes back`() {
            val handled = CountDownLatch(1)
            processorFor { handled.countDown(); error("boom") }.start()

            queue.arrive(messageOf("SendEmail"))
            handled.await(5, SECONDS)

            assertThat(queue.deleted).isEmpty()
        }

        @Test
        fun `does not stop the next one from being processed`() {
            val handled = CountDownLatch(2)
            processorFor { if (it.message.type == "bad") error("boom") else handled.countDown() }.start()

            queue.arrive(messageOf("bad"), messageOf("good"), messageOf("good"))

            assertThat(handled.await(5, SECONDS)).isTrue()
        }
    }

    @Nested
    inner class `the logging context while a message is processed` {
        @Test
        fun `carries the correlation id the message travelled with`() {
            val seen = LinkedBlockingQueue<String>()
            processorFor { seen.add(MDC.get("cid")) }.start()

            queue.arrive(messageOf("SendEmail", cid = "abc123"))

            assertThat(seen.poll(5, SECONDS)).isEqualTo("abc123")
        }

        @Test
        fun `gets one of its own when the message had none`() {
            val seen = LinkedBlockingQueue<String>()
            processorFor { seen.add(MDC.get("cid")) }.start()

            queue.arrive(messageOf("SendEmail"))

            assertThat(seen.poll(5, SECONDS)).isNotBlank()
        }

        @Test
        fun `says the work came from a queue, and which one`() {
            val seen = LinkedBlockingQueue<String>()
            processorFor { seen.add(MDC.get("src")) }.start()

            queue.arrive(messageOf("SendEmail"))

            assertThat(seen.poll(5, SECONDS)).isEqualTo("queue:emails")
        }
    }

    @Nested
    inner class `how many run at once` {
        @Test
        fun `never more than it was told`() {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val release = CountDownLatch(1)
            val handled = CountDownLatch(10)
            processorFor(maxConcurrentWorkers = 2) {
                peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                release.await(5, SECONDS)
                inFlight.decrementAndGet()
                handled.countDown()
            }.start()

            queue.arrive(*Array(10) { messageOf("SendEmail") })
            Thread.sleep(200)
            release.countDown()
            handled.await(5, SECONDS)

            assertThat(peak.get()).isLessThanOrEqualTo(2)
        }
    }

    @Nested
    inner class `the name it reports` {
        @Test
        fun `says which queue it is watching, for a startup log`() {
            assertThat(processorFor { }.name).isEqualTo("MessageQueueProcessor(emails)")
        }
    }

    @Nested
    inner class `the trace` {
        @Test
        fun `processing is a consumer span, child of the send span of the message and linked to it`() {
            val handled = CountDownLatch(1)
            val send = telemetry.tracer.spanBuilder("send emails").startSpan()
            processorFor { handled.countDown() }.start()

            queue.arrive(FakeReceivedMessage("id-7", Message("SendEmail", "{}", traceContext = contextOf(send))))
            handled.await(5, SECONDS)

            val process = telemetry.await(SpanKind.CONSUMER)
            assertThat(process.name).isEqualTo("process emails")
            assertThat(process.traceId).isEqualTo(send.spanContext.traceId)
            assertThat(process.parentSpanId).isEqualTo(send.spanContext.spanId)
            assertThat(process.links.map { it.spanContext.spanId }).containsExactly(send.spanContext.spanId)
            assertThat(process.attributes[stringKey("messaging.system")]).isEqualTo("test_queue")
            assertThat(process.attributes[stringKey("messaging.destination.name")]).isEqualTo("emails")
            assertThat(process.attributes[stringKey("messaging.operation.name")]).isEqualTo("process")
            assertThat(process.attributes[stringKey("messaging.operation.type")]).isEqualTo("process")
            assertThat(process.attributes[stringKey("messaging.message.id")]).isEqualTo("id-7")
            assertThat(process.attributes[stringKey("trantor.message.type")]).isEqualTo("SendEmail")
        }

        @Test
        fun `is current while the message is handled, so what the handler does hangs from it`() {
            val seen = LinkedBlockingQueue<SpanContext>()
            processorFor { seen.add(Span.current().spanContext) }.start()

            queue.arrive(messageOf("SendEmail"))

            assertThat(seen.poll(5, SECONDS)).isEqualTo(telemetry.await(SpanKind.CONSUMER).spanContext)
        }

        @Test
        fun `of a message that fails is failed, naming the exception`() {
            processorFor { error("boom") }.start()

            queue.arrive(messageOf("SendEmail"))

            val process = telemetry.await(SpanKind.CONSUMER)
            assertThat(process.status.statusCode).isEqualTo(StatusCode.ERROR)
            assertThat(process.attributes[stringKey("error.type")]).isEqualTo("java.lang.IllegalStateException")
            assertThat(process.events.map { it.name }).containsExactly("exception")
        }

        @Test
        fun `of a message that brought none starts a new one`() {
            processorFor { }.start()

            queue.arrive(messageOf("SendEmail"))

            val process = telemetry.await(SpanKind.CONSUMER)
            assertThat(process.parentSpanContext.isValid).isFalse()
            assertThat(process.links).isEmpty()
        }
    }

    @Nested
    inner class `stopping` {
        @Test
        fun `it takes no more messages`() {
            val handled = CountDownLatch(1)
            val processor = processorFor { handled.countDown() }
            processor.start()
            queue.arrive(messageOf("SendEmail"))
            handled.await(5, SECONDS)

            processor.stop(2)
            queue.arrive(messageOf("SendEmail"))
            Thread.sleep(300)

            assertThat(queue.polled.get()).isGreaterThan(0)
            assertThat(queue.deleted).hasSize(1)
        }
    }

    @AfterEach
    fun stopWhatWasStarted() {
        processors.forEach { runCatching { it.stop(2) } }
        MDC.clear()
    }

    private fun processorFor(
        maxConcurrentWorkers: Int = 4,
        onMessage: (ReceivedMessage) -> Unit,
    ) = MessageQueueProcessor(queue, onMessage, maxConcurrentWorkers, telemetry.openTelemetry)
        .also { processors.add(it) }

    private fun contextOf(span: Span) = mutableMapOf<String, String>().apply {
        telemetry.openTelemetry.propagators.textMapPropagator
            .inject(Context.root().with(span), this) { carrier, key, value -> carrier!![key] = value }
    }

    private fun messageOf(type: String, cid: String? = null) =
        FakeReceivedMessage("id-${ids.incrementAndGet()}", Message(type, "{}", cid))

    private val ids = AtomicInteger()
    private val telemetry = TestTelemetry()
    private val processors = mutableListOf<MessageQueueProcessor>()
    private val queue = WaitingQueue("emails")
}
