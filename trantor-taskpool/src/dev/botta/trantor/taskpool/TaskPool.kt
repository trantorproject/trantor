package dev.botta.trantor.taskpool

import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.ContextPropagation
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs tasks in the background on virtual threads, at most [TaskPoolSettings.maxConcurrentTasks] at a time.
 *
 * Tasks beyond that wait in a queue of [TaskPoolSettings.queueSize]; when the queue is full a task is rejected
 * right away, its future fails with [RejectedExecutionException] and [TaskPoolSettings.onRejectTask] is told.
 * Every task runs with the logging context and inside the span that were current when it was scheduled.
 */
class TaskPool(val settings: TaskPoolSettings = TaskPoolSettings()): HostedService {
    private val logger = getLogger()
    private val totalSubmitted = AtomicLong(0)
    private val droppedTasks = AtomicLong(0)
    private val middlewares = CopyOnWriteArrayList<TaskPoolMiddleware>()
    // Waiting queue for backpressure
    private val taskQueue = LinkedBlockingQueue<QueuedTask>(settings.queueSize)
    private val semaphore = Semaphore(settings.maxConcurrentTasks)
    private var dispatcherThread: Thread? = null
    private var executor = newExecutor()

    @Volatile private var isRunning = false

    override fun start() {
        if (dispatcherThread != null) error("Already started")
        if (executor.isShutdown) executor = newExecutor()
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
                try {
                    semaphore.acquire()
                } catch (e: InterruptedException) {
                    task.reject(RejectedExecutionException("Task pool stopped before the task could run"))
                    throw e
                }
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
            } catch (e: Throwable) {
                logger.error("Error in dispatch loop", e)
            }
        }
    }

    fun addMiddleware(middleware: TaskPoolMiddleware) {
        middlewares.add(middleware)
    }

    fun <T> schedule(taskId: String? = null, task: () -> T): CompletableFuture<T> {
        if (!isRunning) error("Task pool is not started")

        val context = ContextPropagation.capture()

        totalSubmitted.incrementAndGet()
        val future = CompletableFuture<T>()

        val wrappedTask = Runnable {
            try {
                ContextPropagation.runWithContext(context) {
                    val execute = applyMiddlewares(task)
                    val result = execute()
                    future.complete(result)
                }
            } catch (e: Throwable) {
                logger.error(e.message, e)
                future.completeExceptionally(e)
            }
        }

        val accepted = taskQueue.offer(QueuedTask(wrappedTask) { future.completeExceptionally(it) })
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

    private fun newExecutor(): ExecutorService = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("task-pool-worker-", 0).factory()
    )

    fun getMetrics() = TaskPoolMetrics(
        runningTasks = settings.maxConcurrentTasks - semaphore.availablePermits(),
        queueSize = taskQueue.size,
        totalSubmitted = totalSubmitted.get(),
        droppedTasks = droppedTasks.get()
    )

    /**
     * Stops taking tasks and waits up to [timeoutSeconds] for the running ones, then interrupts them.
     *
     * A task that was waiting for a free slot is rejected. Tasks still in the queue are kept, and run if the pool is
     * started again.
     */
    override fun stop(timeoutSeconds: Int) {
        logger.info("Stopping...")
        isRunning = false
        dispatcherThread?.interrupt() // Force interruption if its blocked
        // A dispatcher still alive after a restart would compete with the new one for the queue and reject what it
        // takes on its way out. Waiting for it also keeps it from submitting to an executor that is already shut down
        dispatcherThread?.join()

        executor.shutdown() // Stops accepting new tasks
        try {
            if (!executor.awaitTermination(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
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

    private class QueuedTask(private val body: Runnable, val reject: (Throwable) -> Unit): Runnable by body
}
