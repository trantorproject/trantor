@file:Suppress("ClassName")

package dev.botta.trantor.core.queues

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.ConfigSection
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class InMemoryQueueTest {
    @Nested
    inner class `polling` {
        @Test
        fun `gives what was enqueued, in the order it arrived`() {
            queue.enqueue(Message("SendEmail", "1"))
            queue.enqueue(Message("SendEmail", "2"))

            assertThat(queue.poll().map { it.message.body }).containsExactly("1", "2")
        }

        @Test
        fun `gives the message whole, with its correlation id and its trace`() {
            val message = Message("SendEmail", "{}", "cid-1", mapOf("traceparent" to "00-abc-def-01"))
            queue.enqueue(message)

            assertThat(queue.poll().single().message).isEqualTo(message)
        }

        @Test
        fun `takes no more than pollMaxMessages at a time`() {
            val queue = queueOf(InMemoryQueueSettings(pollMaxMessages = 2, pollWaitTimeSeconds = 0))
            repeat(3) { queue.enqueue(Message("SendEmail", "$it")) }

            assertThat(queue.poll()).hasSize(2)
            assertThat(queue.poll()).hasSize(1)
        }

        @Test
        fun `gives each message an id of its own, and counts the first delivery as the first attempt`() {
            queue.enqueue(Message("SendEmail", "1"))
            queue.enqueue(Message("SendEmail", "2"))

            val polled = queue.poll()

            assertThat(polled.map { it.id }).doesNotHaveDuplicates()
            assertThat(polled.map { it.attempts }).containsOnly(1)
        }
    }

    @Nested
    inner class `a message being handled` {
        @Test
        fun `is not given to anyone else`() {
            queue.enqueue(Message("SendEmail", "1"))
            queue.poll()

            assertThat(queue.poll()).isEmpty()
        }

        @Test
        fun `comes back when its visibility runs out, as a retry`() {
            queue.enqueue(Message("SendEmail", "1"))
            queue.poll()

            clock.advance(Duration.ofSeconds(60))

            assertThat(queue.poll().single().attempts).isEqualTo(2)
        }

        @Test
        fun `is gone for good once it is deleted`() {
            queue.enqueue(Message("SendEmail", "1"))
            queue.delete(queue.poll().single())

            clock.advance(Duration.ofSeconds(60))

            assertThat(queue.poll()).isEmpty()
            assertThat(queue.size()).isZero()
        }

        @Test
        fun `of another kind of queue cannot be deleted`() {
            val foreign = object: ReceivedMessage {
                override val id = "1"
                override val message = Message("SendEmail", "1")
                override val attempts = 1
            }

            assertThatThrownBy { queue.delete(foreign) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class `a message that keeps coming back` {
        @Test
        fun `is given maxReceiveCount times and then discarded, so one that always fails does not go round for ever`() {
            val queue = queueOf(InMemoryQueueSettings(pollWaitTimeSeconds = 0, maxReceiveCount = 2))
            queue.enqueue(Message("SendEmail", "1"))

            assertThat(attemptsOfEachPoll(queue, polls = 3)).containsExactly(listOf(1), listOf(2), emptyList())
            assertThat(queue.size()).isZero()
        }

        @Test
        fun `is given five times when the settings say nothing`() {
            queue.enqueue(Message("SendEmail", "1"))

            assertThat(attemptsOfEachPoll(queue, polls = 6).flatten()).containsExactly(1, 2, 3, 4, 5)
        }

        @Test
        fun `is given for ever when maxReceiveCount is null`() {
            val queue = queueOf(InMemoryQueueSettings(pollWaitTimeSeconds = 0, maxReceiveCount = null))
            queue.enqueue(Message("SendEmail", "1"))

            assertThat(attemptsOfEachPoll(queue, polls = 20).flatten()).hasSize(20)
        }

        @Test
        fun `does not take the ones behind it with it`() {
            val queue = queueOf(InMemoryQueueSettings(pollWaitTimeSeconds = 0, maxReceiveCount = 1))
            queue.enqueue(Message("SendEmail", "failing"))
            queue.poll()
            queue.enqueue(Message("SendEmail", "new"))

            clock.advance(Duration.ofSeconds(60))

            assertThat(queue.poll().map { it.message.body }).containsExactly("new")
        }

        private fun attemptsOfEachPoll(queue: InMemoryQueue, polls: Int) = (1..polls).map {
            queue.poll().map { it.attempts }.also { clock.advance(Duration.ofSeconds(60)) }
        }
    }

    @Nested
    inner class `a delayed message` {
        @Test
        fun `is not given before its delay`() {
            queue.enqueue(Message("SendEmail", "1"), EnqueueOptions(delaySeconds = 30))

            assertThat(queue.poll()).isEmpty()

            clock.advance(Duration.ofSeconds(30))

            assertThat(queue.poll()).hasSize(1)
        }

        @Test
        fun `does not hold back the ones behind it`() {
            queue.enqueue(Message("SendEmail", "later"), EnqueueOptions(delaySeconds = 30))
            queue.enqueue(Message("SendEmail", "now"))

            assertThat(queue.poll().map { it.message.body }).containsExactly("now")
        }
    }

    @Nested
    inner class `the size` {
        @Test
        fun `counts what is waiting, delayed or being handled, like SQS does`() {
            queue.enqueue(Message("SendEmail", "waiting"))
            queue.enqueue(Message("SendEmail", "delayed"), EnqueueOptions(delaySeconds = 30))
            queue.enqueue(Message("SendEmail", "handled"))
            queue.poll()
            queue.enqueue(Message("SendEmail", "new"))

            assertThat(queue.size()).isEqualTo(4)
        }

        @Test
        fun `is zero after a clear`() {
            queue.enqueue(Message("SendEmail", "1"))

            queue.clear()

            assertThat(queue.size()).isZero()
            assertThat(queue.poll()).isEmpty()
        }
    }

    @Nested
    inner class `an empty poll` {
        @Test
        fun `waits for a message, so the processor that calls it does not spin`() {
            val queue = InMemoryQueue("emails", InMemoryQueueSettings(pollWaitTimeSeconds = 5))
            val polled = executor.submit<List<ReceivedMessage>> { queue.poll() }
            MILLISECONDS.sleep(100)

            queue.enqueue(Message("SendEmail", "1"))

            assertThat(polled.get(2, SECONDS)).hasSize(1)
        }

        @Test
        fun `gives up after pollWaitTimeSeconds`() {
            val queue = InMemoryQueue("emails", InMemoryQueueSettings(pollWaitTimeSeconds = 1))
            val start = System.nanoTime()

            assertThat(queue.poll()).isEmpty()
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900))
        }

        @Test
        fun `stops waiting when its thread is interrupted, which is how a processor stops`() {
            val queue = InMemoryQueue("emails", InMemoryQueueSettings(pollWaitTimeSeconds = 20))
            val thrown = AtomicReference<Throwable>()
            val poller = Thread.ofVirtual().start { runCatching { queue.poll() }.onFailure { thrown.set(it) } }
            MILLISECONDS.sleep(100)

            poller.interrupt()

            assertThat(poller.join(Duration.ofSeconds(2))).isTrue()
            assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        }
    }

    @Nested
    inner class `the factory` {
        @Test
        fun `takes the settings from the section the queue is declared in`() {
            val config = ConfigManager().addMemoryCollection(
                "jobs.queues.emails.driver" to "memory",
                "jobs.queues.emails.pollVisibilityTimeout" to "300",
            )

            val queue = queueFrom(config.getSection("jobs.queues.emails"))

            assertThat(queue.name).isEqualTo("emails")
            assertThat(queue.settings.pollVisibilityTimeout).isEqualTo(300)
            assertThat(queue.settings.pollWaitTimeSeconds).isEqualTo(20)
        }

        @Test
        fun `uses the defaults when the section says nothing else`() {
            val config = ConfigManager().addMemoryCollection("jobs.queues.emails.driver" to "memory")

            val queue = queueFrom(config.getSection("jobs.queues.emails"))

            assertThat(queue.settings).isEqualTo(InMemoryQueueSettings())
        }

        private fun queueFrom(section: ConfigSection) =
            InMemoryQueueFactory(GsonSerializer()).createFromConfig("emails", section) as InMemoryQueue
    }

    private fun queueOf(settings: InMemoryQueueSettings) = InMemoryQueue("emails", settings, clock)

    private val clock = MutableClock()
    private val queue = queueOf(InMemoryQueueSettings(pollWaitTimeSeconds = 0))
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    private class MutableClock(private var now: Instant = Instant.parse("2026-10-01T12:00:00Z")): Clock() {
        fun advance(duration: Duration) {
            now += duration
        }

        override fun instant() = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this
    }
}
