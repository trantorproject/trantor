package dev.botta.trantor.core.application

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request

class DefaultApplicationExecutor(private val cqBus: CQBus): ApplicationExecutor {
    override fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    override fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities) {
        cqBus.registerMiddleware(middleware, priority)
    }
}
