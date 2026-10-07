@file:Suppress("ClassName")

package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.jobs.serialization.DefaultJobSerializer
import dev.botta.trantor.core.testing.WaitingQueue
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

class JobProcessorTest {
    @Nested
    inner class `a job that arrives` {
        @Test
        fun `is handed to its handler, with its contents`() {
            val executed = LinkedBlockingQueue<String>()
            registerSendEmail { executed.add(it.to) }
            processor().start()

            queue.arrive("SendEmail", """{"to":"nico@example.com"}""")

            assertThat(executed.poll(5, SECONDS)).isEqualTo("nico@example.com")
        }

        @Test
        fun `is deleted once it ran, so it does not run twice`() {
            val executed = CountDownLatch(1)
            registerSendEmail { executed.countDown() }
            processor().start()

            queue.arrive("SendEmail", """{"to":"nico@example.com"}""")
            executed.await(5, SECONDS)

            assertThat(queue.awaitDeleted()).isEqualTo(1)
        }

        @Test
        fun `is found by its job type and not by its class name`() {
            val executed = CountDownLatch(1)
            serializer.register(RebuildReport::class)
            handlers.registerHandler(RebuildReport::class, JobHandler { executed.countDown() })
            processor().start()

            queue.arrive("reports.rebuild.v2", "{}")

            assertThat(executed.await(5, SECONDS)).isTrue()
        }
    }

    @Nested
    inner class `a job that fails` {
        @Test
        fun `is left on the queue, so the queue retries it`() {
            val executed = CountDownLatch(1)
            registerSendEmail { executed.countDown(); error("the mail server is down") }
            processor().start()

            queue.arrive("SendEmail", """{"to":"nico@example.com"}""")
            executed.await(5, SECONDS)
            Thread.sleep(300)

            assertThat(queue.deleted).isEmpty()
        }

        @Test
        fun `a job with no handler is left on the queue too, because it may be a deploy in progress`() {
            serializer.register(SendEmail::class)
            processor().start()

            queue.arrive("SendEmail", """{"to":"nico@example.com"}""")
            Thread.sleep(500)

            assertThat(queue.deleted).isEmpty()
        }
    }

    @Nested
    inner class `a message that is not a job this application knows` {
        @Test
        fun `is left on the queue, because it may be a job of the release being deployed`() {
            processor().start()

            queue.arrive("JobOfTheNextRelease", "{}")
            Thread.sleep(500)

            assertThat(queue.deleted).isEmpty()
        }

        @Test
        fun `a body that does not parse is left too, so it ends where the queue puts what fails and not nowhere`() {
            serializer.register(SendEmail::class)
            processor().start()

            queue.arrive("SendEmail", """{"to":{"not":"a string"}}""")
            Thread.sleep(500)

            assertThat(queue.deleted).isEmpty()
        }

        @Test
        fun `and the ones after it still run`() {
            val executed = CountDownLatch(1)
            registerSendEmail { executed.countDown() }
            processor().start()

            queue.arrive("JobOfTheNextRelease", "{}")
            queue.arrive("SendEmail", """{"to":"nico@example.com"}""")

            assertThat(executed.await(5, SECONDS)).isTrue()
        }
    }

    @Test
    fun `says which queue it is watching, for a startup log`() {
        assertThat(processor().name).isEqualTo("JobProcessor(emails)")
    }

    @AfterEach
    fun stopWhatWasStarted() {
        processors.forEach { runCatching { it.stop(2) } }
    }

    private fun processor() = JobProcessor(handlers, serializer, queue).also { processors.add(it) }

    private fun registerSendEmail(execute: (SendEmail) -> Unit) {
        serializer.register(SendEmail::class)
        handlers.registerHandler(SendEmail::class, JobHandler { execute(it) })
    }

    class SendEmail(val to: String = ""): Job()

    @JobType("reports.rebuild.v2")
    class RebuildReport: Job()

    private fun <T: Job> JobHandler(execute: (T) -> Unit) = object: JobHandler<T> {
        override fun execute(job: T) = execute(job)
    }

    private val processors = mutableListOf<JobProcessor>()
    private val queue = WaitingQueue("emails")
    private val handlers = JobHandlerRegistry()
    private val serializer = DefaultJobSerializer(GsonSerializer())
}
