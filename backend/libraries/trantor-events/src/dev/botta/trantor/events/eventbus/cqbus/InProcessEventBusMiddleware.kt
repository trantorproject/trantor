package dev.botta.trantor.events.eventbus.cqbus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.events.eventbus.InProcessEventBus

class InProcessEventBusMiddleware(private val eventBus: InProcessEventBus): Middleware {
    override fun <T : Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        return eventBus.inRequest { next(request) }
    }
}
