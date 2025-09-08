package dev.botta.trantor.core.concurrent.taskPool

import dev.botta.trantor.core.getLogger
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class TaskPool(val settings: TaskPoolSettings = TaskPoolSettings()): AutoCloseable {
    private val logger = getLogger()
    private val totalSubmitted = AtomicLong(0)
    private val droppedTasks = AtomicLong(0)
    private val middlewares = mutableListOf<TaskPoolMiddleware>()
    private val taskQueue = LinkedBlockingQueue<Runnable>(settings.queueSize)

    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        settings.threadCount,
        settings.threadCount,
        0L,
        TimeUnit.MILLISECONDS,
        taskQueue,
        Executors.defaultThreadFactory(),
        RejectedExecutionHandler { task, exec ->
            val dropped = droppedTasks.incrementAndGet()
            settings.onRejectTask(task)
            logger.warn("Rejecting task because of empty queue. Total rejected: $dropped. Queue size: ${exec.queue.size}")
        }
    )

    fun addMiddleware(middleware: TaskPoolMiddleware) {
        middlewares.add(middleware)
    }

    fun <T> schedule(task: () -> T): CompletableFuture<T> {
        totalSubmitted.incrementAndGet()
        val future = CompletableFuture<T>()
        try {
            executor.submit {
                try {
                    val execute = applyMiddlewares(task)
                    val result = execute()
                    future.complete(result)
                } catch (e: Exception) {
                    logger.error(e.message, e)
                    future.completeExceptionally(e)
                }
            }
        } catch (e: RejectedExecutionException) {
            logger.error(e.message, e)
            future.completeExceptionally(e)
        }

        return future
    }

    private fun <T> applyMiddlewares(execute: () -> T): () -> T {
        var newExecute = execute
        for (middleware in middlewares) {
            val previousFunc = newExecute
            newExecute = { middleware.execute(previousFunc) }
        }
        return newExecute
    }

    fun getMetrics() = TaskPoolMetrics(
        executor.activeCount,
        executor.poolSize,
        executor.queue.size,
        executor.completedTaskCount,
        totalSubmitted.get(),
        droppedTasks.get()
    )

    fun shutdown(gracefully: Boolean = true) {
        if (gracefully) {
            logger.info("Gracefully shutting down...")
            executor.shutdown()
            return
        }
        logger.info("Force shutting down...")
        executor.shutdownNow()
    }

    override fun close() {
        logger.info("Gracefully shutting down...")
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            logger.warn("Shutdown timeout. Force shutdown")
            executor.shutdownNow()
        }
    }
}
