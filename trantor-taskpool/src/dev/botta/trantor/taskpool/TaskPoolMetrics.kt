package dev.botta.trantor.taskpool

data class TaskPoolMetrics(
    val runningTasks: Int,
    val queueSize: Int,
    val totalSubmitted: Long,
    val droppedTasks: Long,
)
