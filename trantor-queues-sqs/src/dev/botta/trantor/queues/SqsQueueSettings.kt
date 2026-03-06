package dev.botta.trantor.queues

data class SqsQueueSettings(
    var region: String = "us-east-1",
    var endpointOverride: String? = null,
    var pollMaxMessages: Int = 10,
    var pollWaitTimeSeconds: Int = 20,
    var pollVisibilityTimeout: Int = 60,
)
