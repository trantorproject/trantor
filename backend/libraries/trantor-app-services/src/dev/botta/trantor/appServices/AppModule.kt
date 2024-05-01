package dev.botta.trantor.appServices

import dev.botta.cqbus.*
import dev.botta.cqbus.MiddlewarePriorities.Low
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.appServices.middlewares.*
import dev.botta.trantor.core.Event
import dev.botta.trantor.eventBus.EventBus
import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.tx.TransactionManager

abstract class AppModule(protected val services: ServiceProvider) {
    protected val cqBus = services.get<CQBus>()
    protected val eventBus = services.get<EventBus>()

    init {
        cqBus.registerMiddleware(AuthorizationMiddleware(), Low)
        cqBus.registerMiddleware(LoggingMiddleware(), Low)
        cqBus.registerMiddleware(TransactionalMiddleware(services.get<TransactionManager>()), Low)
    }

    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    fun notify(event: Event) {
        eventBus.publish(event)
    }

    fun registerMiddleware(middleware: Middleware) {
        cqBus.registerMiddleware(middleware)
    }
}
