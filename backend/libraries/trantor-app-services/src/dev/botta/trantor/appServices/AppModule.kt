package dev.botta.trantor.appServices

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.Event
import dev.botta.trantor.eventBus.EventBus
import dev.botta.trantor.serviceProvider.*

abstract class AppModule(protected val services: ServiceProvider): CQEDispatcher {
    private val cqBus = services.get<CQBus>()
    private val eventBus = services.get<EventBus>()

    override fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return cqBus.execute(request, context)
    }

    override fun notify(event: Event) {
        eventBus.publish(event)
    }

    override fun registerMiddleware(middleware: Middleware) {
        cqBus.registerMiddleware(middleware)
    }
}
