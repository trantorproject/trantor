package dev.botta.trantor.data.coroutines

import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

// Threads size must be similar to max db connections
class DefaultDbDispatcherProvider(threads: Int = 10): DbDispatcherProvider {
    private val dispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(
            threads,
            object : ThreadFactory {
                private val count = AtomicInteger(0)

                override fun newThread(r: Runnable): Thread {
                    val id = count.incrementAndGet()
                    return Thread(r, "db-dispatcher-$id")
                }
            }
        ).asCoroutineDispatcher()
    }

    override fun get() = dispatcher
}
