package dev.botta.trantor.eventBus.cqbus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.eventBus.InProcessEventBus

class InProcessEventBusMiddleware(private val eventBus: InProcessEventBus): Middleware {
    override suspend fun <T : Request<R>, R> execute(request: T, next: suspend (T) -> R, context: ExecutionContext): R {
        return eventBus.inRequest { next(request) }
    }
}
