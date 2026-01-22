package dev.botta.trantor.taskpool

interface TaskPoolMiddleware {
    fun <T> execute(next: () -> T): T
}
