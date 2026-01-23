package dev.botta.trantor.taskpool

data class TaskPoolSettings(
    var maxConcurrentTasks: Int = 4,
    var queueSize: Int = 100,
    var onRejectTask: (taskId: String?) -> Unit = {},
)
