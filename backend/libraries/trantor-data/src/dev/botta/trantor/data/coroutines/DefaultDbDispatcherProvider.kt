package dev.botta.trantor.data.coroutines

import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

// Threads size must be similar to max db connections
class DefaultDbDispatcherProvider(threads: Int = 10): DbDispatcherProvider {
    private val executor: ExecutorService by lazy {
        val executor = Executors.newFixedThreadPool(
            threads,
            object: ThreadFactory {
                private val count = AtomicInteger(0)

                override fun newThread(r: Runnable): Thread {
                    val id = count.incrementAndGet()
                    return Thread(r, "db-dispatcher-$id").apply { isDaemon = true }
                }
            }
        )
        executor
    }

    private val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }

    override fun get() = dispatcher
}
