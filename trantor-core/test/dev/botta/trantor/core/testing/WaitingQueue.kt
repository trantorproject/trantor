package dev.botta.trantor.core.testing

import dev.botta.trantor.core.queues.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * A queue that hands over what a test puts in it.
 *
 * [poll] waits like long polling does, because the poller loop of [MessageQueueProcessor] has no pause of
 * its own and would otherwise spin on an empty queue.
 */
class WaitingQueue(override val name: String = "emails"): MessageQueue {
    val deleted = ConcurrentLinkedQueue<ReceivedMessage>()
    val polled = AtomicInteger()

    private val ids = AtomicInteger()
    private val pending = LinkedBlockingQueue<ReceivedMessage>()
    private val deletions = LinkedBlockingQueue<ReceivedMessage>()

    fun arrive(vararg messages: ReceivedMessage) {
        messages.forEach { pending.put(it) }
    }

    fun arrive(type: String, body: String = "{}", cid: String? = null) {
        arrive(FakeReceivedMessage("id-${ids.incrementAndGet()}", Message(type, body, cid)))
    }

    /** Waits for the next deletion and answers how many there have been. */
    fun awaitDeleted(): Int {
        deletions.poll(5, SECONDS)

        return deleted.size
    }

    override fun enqueue(message: Message, options: EnqueueOptions) {}

    override fun poll(): List<ReceivedMessage> {
        polled.incrementAndGet()
        val first = pending.poll(200, MILLISECONDS) ?: return emptyList()
        val batch = mutableListOf(first)
        pending.drainTo(batch)

        return batch
    }

    override fun clear() {}

    override fun size() = pending.size

    override fun delete(message: ReceivedMessage) {
        deleted.add(message)
        deletions.add(message)
    }
}

class FakeReceivedMessage(
    override val id: String,
    override val message: Message,
    override val attempts: Int = 1,
): ReceivedMessage
