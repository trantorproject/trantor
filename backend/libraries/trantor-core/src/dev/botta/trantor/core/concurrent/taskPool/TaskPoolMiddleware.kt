package dev.botta.trantor.core.concurrent.taskPool

interface TaskPoolMiddleware {
    fun <T> execute(next: () -> T): T
}
