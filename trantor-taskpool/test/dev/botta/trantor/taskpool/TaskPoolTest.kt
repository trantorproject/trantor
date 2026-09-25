@file:Suppress("ClassName")

package dev.botta.trantor.taskpool

import dev.botta.trantor.primitives.MdcPropagation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.system.measureTimeMillis

class TaskPoolTest {
    @Nested
    inner class `running a task` {
        @Test
        fun `the future carries what it returned`() {
            val pool = started()

            val result = pool.schedule { 2 + 2 }

            assertThat(result.get(5, SECONDS)).isEqualTo(4)
        }

        @Test
        fun `a task that throws fails the future instead of disappearing`() {
            val pool = started()

            val result = pool.schedule<Unit> { error("boom") }

            assertThatThrownBy { result.get(5, SECONDS) }.hasRootCauseMessage("boom")
        }

        @Test
        fun `one task failing does not stop the next`() {
            val pool = started()

            pool.schedule<Unit> { error("boom") }

            assertThat(pool.schedule { "still here" }.get(5, SECONDS)).isEqualTo("still here")
        }

        @Test
        fun `several tasks all run`() {
            val pool = started()

            val results = (1..20).map { n -> pool.schedule { n * 2 } }

            assertThat(results.map { it.get(5, SECONDS) }).containsExactlyElementsOf((1..20).map { it * 2 })
        }
    }

    @Nested
    inner class `the logging context` {
        @Test
        fun `travels with the task, so its logs are still traceable`() {
            val pool = started()
            MDC.put("correlationId", "abc123")

            val seen = pool.schedule { MDC.get("correlationId") }

            assertThat(seen.get(5, SECONDS)).isEqualTo("abc123")
        }

        @Test
        fun `is the one captured when the task was scheduled, not when it ran`() {
            val pool = started()
            MDC.put("correlationId", "abc123")
            val seen = pool.schedule { MDC.get("correlationId") }

            MDC.put("correlationId", "changed")

            assertThat(seen.get(5, SECONDS)).isEqualTo("abc123")
        }

        @Test
        fun `a task without a context gets none, not the one from another task`() {
            val pool = started()

            val seen = pool.schedule { MdcPropagation.capture() }

            assertThat(seen.get(5, SECONDS)).isEmpty()
        }
    }

    @Nested
    inner class `middlewares` {
        @Test
        fun `wrap the task`() {
            val pool = started()
            pool.addMiddleware(Recording("outer", calls))

            pool.schedule { calls.add("task") }.get(5, SECONDS)

            assertThat(calls).containsExactly("outer in", "task", "outer out")
        }

        @Test
        fun `the last one added is the outermost`() {
            val pool = started()
            pool.addMiddleware(Recording("first", calls))
            pool.addMiddleware(Recording("second", calls))

            pool.schedule { calls.add("task") }.get(5, SECONDS)

            assertThat(calls).containsExactly("second in", "first in", "task", "first out", "second out")
        }
    }

    @Nested
    inner class `when the queue is full` {
        @Test
        fun `the task is rejected instead of piling up`() {
            val blocked = CountDownLatch(1)
            val pool = started(TaskPoolSettings(maxConcurrentTasks = 1, queueSize = 2))

            val results = (1..50).map { pool.schedule { blocked.await(5, SECONDS) } }
            blocked.countDown()

            assertThat(results.count { it.isCompletedExceptionally }).isGreaterThan(0)
            assertThatThrownBy { results.first { it.isCompletedExceptionally }.get(5, SECONDS) }
                .hasCauseInstanceOf(RejectedExecutionException::class.java)
        }

        @Test
        fun `whoever asked to know is told which task it was`() {
            val blocked = CountDownLatch(1)
            val rejected = mutableListOf<String?>()
            val pool = started(
                TaskPoolSettings(maxConcurrentTasks = 1, queueSize = 2, onRejectTask = { rejected.add(it) }),
            )

            (1..50).forEach { n -> pool.schedule("task-$n") { blocked.await(5, SECONDS) } }
            blocked.countDown()

            assertThat(rejected).isNotEmpty()
            assertThat(rejected).allMatch { it != null && it.startsWith("task-") }
        }
    }

    @Nested
    inner class `metrics` {
        @Test
        fun `count everything that was handed over`() {
            val pool = started()

            (1..5).map { pool.schedule { it } }.forEach { it.get(5, SECONDS) }

            assertThat(pool.getMetrics().totalSubmitted).isEqualTo(5)
            assertThat(pool.getMetrics().droppedTasks).isZero()
        }

        @Test
        fun `count what was turned away`() {
            val blocked = CountDownLatch(1)
            val pool = started(TaskPoolSettings(maxConcurrentTasks = 1, queueSize = 2))

            (1..50).forEach { pool.schedule { blocked.await(5, SECONDS) } }
            blocked.countDown()

            assertThat(pool.getMetrics().totalSubmitted).isEqualTo(50)
            assertThat(pool.getMetrics().droppedTasks).isGreaterThan(0)
        }
    }

    @Nested
    inner class `the lifecycle` {
        @Test
        fun `scheduling before it started says so, instead of losing the task`() {
            val pool = TaskPool()

            assertThatThrownBy { pool.schedule { 1 } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("not started")
        }

        @Test
        fun `starting twice says so`() {
            val pool = started()

            assertThatThrownBy { pool.start() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Already started")
        }

        @Test
        fun `after stopping it takes no more tasks`() {
            val pool = started()

            pool.stop()

            assertThatThrownBy { pool.schedule { 1 } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("not started")
        }

        @Test
        fun `it can be started again after it was stopped`() {
            val pool = started()
            pool.stop()

            pool.start()

            assertThat(pool.schedule { "back" }.get(5, SECONDS)).isEqualTo("back")
        }

        @Test
        fun `a task that was waiting for a free slot when it stopped is rejected, not lost`() {
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = started(TaskPoolSettings(maxConcurrentTasks = 1))
            pool.schedule { running.countDown(); release.await(5, SECONDS) }
            running.await(5, SECONDS)
            val waiting = pool.schedule { "never runs" }
            awaitUntil { pool.getMetrics().queueSize == 0 }

            val stopping = Thread.ofVirtual().start { pool.stop() }

            assertThatThrownBy { waiting.get(5, SECONDS) }.hasCauseInstanceOf(RejectedExecutionException::class.java)
            release.countDown()
            stopping.join()
        }

        @Test
        fun `stopping waits for running tasks no longer than it was told`() {
            val running = CountDownLatch(1)
            val pool = started()
            pool.schedule { running.countDown(); CountDownLatch(1).await(30, SECONDS) }
            running.await(5, SECONDS)

            val elapsed = measureTimeMillis { pool.stop(timeoutSeconds = 1) }

            assertThat(elapsed).isLessThan(5_000)
        }
    }

    @AfterEach
    fun stopWhatWasStarted() {
        pools.forEach { runCatching { it.stop() } }
        MDC.clear()
    }

    private fun started(settings: TaskPoolSettings = TaskPoolSettings()) =
        TaskPool(settings).also { pools.add(it) }.apply { start() }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition was not met in time" }
            Thread.sleep(10)
        }
    }

    private class Recording(private val name: String, private val calls: MutableList<String>): TaskPoolMiddleware {
        override fun <T> execute(next: () -> T): T {
            calls.add("$name in")

            return next().also { calls.add("$name out") }
        }
    }

    private val calls = mutableListOf<String>()
    private val pools = mutableListOf<TaskPool>()
}
