package dev.botta.trantor.core.queues

/** The settings of an [InMemoryQueue], named as those of SQS so a queue can change driver and keep them. */
data class InMemoryQueueSettings(
    var pollMaxMessages: Int = 10,
    /** How long a poll waits for a message. Waiting costs nothing here: an enqueue wakes it at once. */
    var pollWaitTimeSeconds: Int = 20,
    /** How long a message being handled stays hidden before it is given again, in seconds. */
    var pollVisibilityTimeout: Int = 60,
)
