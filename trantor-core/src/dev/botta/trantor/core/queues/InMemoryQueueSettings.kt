package dev.botta.trantor.core.queues

/** The settings of an [InMemoryQueue], named as those of SQS so a queue can change driver and keep them. */
data class InMemoryQueueSettings(
    var pollMaxMessages: Int = 10,
    /** How long a poll waits for a message. Waiting costs nothing here: an enqueue wakes it at once. */
    var pollWaitTimeSeconds: Int = 20,
    /** How long a message being handled stays hidden before it is given again, in seconds. */
    var pollVisibilityTimeout: Int = 60,
    /**
     * How many times a message is given before the queue discards it, as the `maxReceiveCount` of an SQS redrive
     * policy, without the dead letter queue: the message is lost, with an error in the log. `null` gives it for ever.
     */
    var maxReceiveCount: Int? = 5,
)
