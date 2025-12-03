package dev.botta.trantor.appServices

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.serviceProvider.ServiceProvider

abstract class AppModule(protected val services: ServiceProvider) {
    protected val cqBus = services.getOrDefault { CQBus() }

    open fun start() {}

    open fun shutdown() {}

    suspend fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities = MiddlewarePriorities.Normal) {
        cqBus.registerMiddleware(middleware, priority)
    }
}
