package dev.botta.trantor.core.concurrent.taskPool

data class TaskPoolSettings(
    val threadCount: Int = 4,
    val queueSize: Int = 100,
    val onRejectTask: (task: Runnable) -> Unit = {},
)
