package dev.botta.trantor.eventBus.taskPool

import dev.botta.trantor.core.concurrent.taskPool.TaskPoolMiddleware
import dev.botta.trantor.eventBus.InProcessEventBus

class InProcessEventBusTaskPoolMiddleware(private val eventBus: InProcessEventBus): TaskPoolMiddleware {
    override fun <T> execute(next: () -> T): T {
        eventBus.preRequest()
        val result = next()
        eventBus.postRequest()
        return result
    }
}
