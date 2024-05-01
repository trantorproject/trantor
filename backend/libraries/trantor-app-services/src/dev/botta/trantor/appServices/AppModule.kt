package dev.botta.trantor.appServices

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.Event
import dev.botta.trantor.eventBus.EventBus

abstract class AppModule(protected val eventBus: EventBus) {
    protected val cqBus = CQBus()

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
