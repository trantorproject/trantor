package dev.botta.trantor.core.app

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.HostedService

abstract class AppModule(protected val services: ServiceProvider): HostedService {
    protected val cqBus = services.getOrDefault { CQBus() }

    override fun start() {}

    override fun stop(timeoutSeconds: Int) {}

    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities = MiddlewarePriorities.Normal) {
        cqBus.registerMiddleware(middleware, priority)
    }
}
