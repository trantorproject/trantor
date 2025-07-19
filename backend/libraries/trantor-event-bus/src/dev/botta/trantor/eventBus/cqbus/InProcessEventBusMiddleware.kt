package dev.botta.trantor.eventBus.cqbus

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.Middleware
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.eventBus.InProcessEventBus

class InProcessEventBusMiddleware(private val eventBus: InProcessEventBus): Middleware {
    override fun <T : Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        eventBus.preRequest()
        val response = next(request)
        eventBus.postRequest()
        return response
    }
}
