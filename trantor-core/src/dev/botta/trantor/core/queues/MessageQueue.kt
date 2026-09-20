package dev.botta.trantor.core.queues

/**
 * A queue of messages, polled by a [MessageQueueProcessor].
 *
 * **[poll] is what paces the processor.** Its loop has no wait of its own between one poll and the next,
 * so a queue that answers an empty list immediately is asked again immediately: a busy loop that burns a
 * carrier thread and turns into a flood of queries, api calls or billed requests. An implementation is
 * therefore expected to block until it has something or until a wait of its own expires. SqsQueue does it
 * with long polling, twenty seconds by default.
 *
 * A message stays on the queue until [delete] takes it off, which is what makes a failure a retry: work
 * that threw is simply never deleted and comes back when its visibility runs out.
 */
interface MessageQueue {
    val name: String

    fun enqueue(message: Message, options: EnqueueOptions = EnqueueOptions())
    fun poll(): List<ReceivedMessage>
    fun clear()
    fun size(): Int?
    fun delete(message: ReceivedMessage)
}
