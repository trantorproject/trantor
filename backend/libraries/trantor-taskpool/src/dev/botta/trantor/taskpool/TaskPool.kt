package dev.botta.trantor.taskpool

import dev.botta.trantor.core.logging.getLogger
import dev.botta.trantor.hosting.HostedService
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class TaskPool(val settings: TaskPoolSettings = TaskPoolSettings()): HostedService {
    private val logger = getLogger()
    private val totalSubmitted = AtomicLong(0)
    private val droppedTasks = AtomicLong(0)
    private val middlewares = CopyOnWriteArrayList<TaskPoolMiddleware>()
    // Waiting queue for backpressure
    private val taskQueue = LinkedBlockingQueue<Runnable>(settings.queueSize)
    private val semaphore = Semaphore(settings.maxConcurrentTasks)
    private var dispatcherThread: Thread? = null
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("task-pool-worker-", 0)
        .factory()
    )

    @Volatile private var isRunning = false

    override fun start() {
        if (dispatcherThread != null) error("Already started")
        isRunning = true
        dispatcherThread = Thread.ofVirtual().name("task-pool-dispatcher").start {
            dispatchLoop()
        }
        logger.info("Started")
    }

    private fun dispatchLoop() {
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                // Take next task from queue, blocks if empty
                val task = taskQueue.take()
                // Acquire concurrency permissions, blocks if maxConcurrentTasks
                semaphore.acquire()
                executor.submit {
                    try {
                        task.run()
                    } finally {
                        semaphore.release()
                    }
                }
            } catch (e: InterruptedException) {
                logger.info("Dispatcher interrupted, stopping...")
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                logger.error("Error in dispatch loop", e)
            }
        }
    }

    fun addMiddleware(middleware: TaskPoolMiddleware) {
        middlewares.add(middleware)
    }

    fun <T> schedule(taskId: String? = null, task: () -> T): CompletableFuture<T> {
        if (!isRunning) error("Task pool is not started")

        totalSubmitted.incrementAndGet()
        val future = CompletableFuture<T>()

        val wrappedTask = Runnable {
            try {
                val execute = applyMiddlewares(task)
                val result = execute()
                future.complete(result)
            } catch (e: Exception) {
                logger.error(e.message, e)
                future.completeExceptionally(e)
            }
        }

        val accepted = taskQueue.offer(wrappedTask)
        if (!accepted) {
            val dropped = droppedTasks.incrementAndGet()
            settings.onRejectTask(taskId)
            val msg = "Rejecting task because queue is full. Total rejected: $dropped. Queue size: ${taskQueue.size}"
            logger.warn(msg)
            future.completeExceptionally(RejectedExecutionException(msg))
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
        runningTasks = settings.maxConcurrentTasks - semaphore.availablePermits(),
        queueSize = taskQueue.size,
        totalSubmitted = totalSubmitted.get(),
        droppedTasks = droppedTasks.get()
    )

    override fun stop(timeoutSeconds: Int) {
        logger.info("Stopping...")
        isRunning = false
        dispatcherThread?.interrupt() // Force interruption if its blocked

        executor.shutdown() // Stops accepting new tasks
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Shutdown timeout. Force shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        dispatcherThread = null
        logger.info("TaskPool shutdown complete.")
    }
}
