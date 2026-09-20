@file:Suppress("ClassName")

package dev.botta.trantor.core.queues

import dev.botta.trantor.core.testing.FakeReceivedMessage
import dev.botta.trantor.core.testing.WaitingQueue
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
    ) = MessageQueueProcessor(queue, onMessage, maxConcurrentWorkers).also { processors.add(it) }

    private fun messageOf(type: String, cid: String? = null) =
        FakeReceivedMessage("id-${ids.incrementAndGet()}", Message(type, "{}", cid))

    private val ids = AtomicInteger()
    private val processors = mutableListOf<MessageQueueProcessor>()
    private val queue = WaitingQueue("emails")
}
