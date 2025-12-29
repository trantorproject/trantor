package dev.botta.trantor.core.concurrent.taskPool

data class TaskPoolSettings(
    val maxConcurrentTasks: Int = 4,
    val queueSize: Int = 100,
    val onRejectTask: (taskId: String?) -> Unit = {},
)
