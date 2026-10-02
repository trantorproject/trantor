package dev.botta.trantor.core.queues

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [MessageQueue] in the memory of the process, for development, for tests, and for an application that runs as a
 * single instance and can afford to lose what is pending when it stops.
 *
 * It behaves like SQS where a job can tell: [poll] waits up to [InMemoryQueueSettings.pollWaitTimeSeconds] for a
 * message, a message polled is hidden for [InMemoryQueueSettings.pollVisibilityTimeout] seconds and comes back with
 * one more attempt unless it is deleted, and [EnqueueOptions.delaySeconds] holds a message back. The group and
 * deduplication ids are ignored.
 *
 * What it holds dies with the process, and each instance of an application has a queue of its own: a job dispatched
 * by one is only run by that one.
 */
class InMemoryQueue(
    override val name: String,
    val settings: InMemoryQueueSettings = InMemoryQueueSettings(),
    private val clock: Clock = Clock.systemUTC(),
): MessageQueue {
    override val system = "memory"

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val entries = mutableListOf<Entry>()
    private val ids = AtomicLong()

    override fun enqueue(message: Message, options: EnqueueOptions) = lock.withLock {
        val visibleAt = clock.instant().plusSeconds(options.delaySeconds.toLong())
        entries += Entry(ids.incrementAndGet().toString(), message, visibleAt)
        changed.signalAll()
    }

    override fun poll(): List<ReceivedMessage> {
        // The wait is measured with nanoTime and not with the clock, which a test may have stopped
        val deadline = System.nanoTime() + SECONDS.toNanos(settings.pollWaitTimeSeconds.toLong())
        lock.lockInterruptibly()
        try {
            while (true) {
                val now = clock.instant()
                val visible = entries.filter { !it.visibleAt.isAfter(now) }.take(settings.pollMaxMessages)
                if (visible.isNotEmpty()) return visible.map { receive(it, now) }

                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return emptyList()

                // Woken by an enqueue, or when the first hidden message is due
                val untilDue = entries.minOfOrNull { Duration.between(now, it.visibleAt).toNanos() } ?: remaining
                changed.awaitNanos(minOf(remaining, untilDue).coerceAtLeast(1))
            }
        } finally {
            lock.unlock()
        }
    }

    private fun receive(entry: Entry, now: Instant): ReceivedMessage {
        entry.visibleAt = now.plusSeconds(settings.pollVisibilityTimeout.toLong())
        entry.attempts++
        return InMemoryReceivedMessage(entry.id, entry.message, entry.attempts)
    }

    override fun delete(message: ReceivedMessage) = lock.withLock {
        require(message is InMemoryReceivedMessage) { "Queue '$name' can only delete the messages it gave" }
        entries.removeIf { it.id == message.id }
        Unit
    }

    override fun clear() = lock.withLock { entries.clear() }

    /** Every message it holds, the delayed ones and the ones being handled included, as SQS counts them. */
    override fun size() = lock.withLock { entries.size }

    private class Entry(val id: String, val message: Message, var visibleAt: Instant, var attempts: Int = 0)
}

class InMemoryReceivedMessage(
    override val id: String,
    override val message: Message,
    override val attempts: Int,
): ReceivedMessage
