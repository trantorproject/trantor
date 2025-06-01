package dev.botta.trantor.appServices

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.Event
import dev.botta.trantor.eventBus.*
import dev.botta.trantor.serviceProvider.*

abstract class AppModule(protected val services: ServiceProvider) {
    protected val cqBus = services.getOrDefault { CQBus() }
    protected val eventBus: EventBus = services.getOrDefault { InProcessEventBus() }

    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    fun notify(event: Event) {
        eventBus.publish(event)
    }

    fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities = MiddlewarePriorities.Normal) {
        cqBus.registerMiddleware(middleware, priority)
    }
}
