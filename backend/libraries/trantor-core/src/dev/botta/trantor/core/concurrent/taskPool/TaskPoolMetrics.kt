package dev.botta.trantor.core.concurrent.taskPool

data class TaskPoolMetrics(
    val runningTasks: Int,
    val queueSize: Int,
    val totalSubmitted: Long,
    val droppedTasks: Long,
)
