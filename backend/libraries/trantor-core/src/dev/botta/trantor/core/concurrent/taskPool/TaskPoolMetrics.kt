package dev.botta.trantor.core.concurrent.taskPool

data class TaskPoolMetrics(
    val activeThreads: Int,
    val poolSize: Int,
    val queueSize: Int,
    val completedTasks: Long,
    val totalSubmitted: Long,
    val droppedTasks: Long,
)
