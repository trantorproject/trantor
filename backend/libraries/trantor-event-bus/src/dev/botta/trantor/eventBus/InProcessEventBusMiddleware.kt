package dev.botta.trantor.eventBus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request

class InProcessEventBusMiddleware(private val eventBus: InProcessEventBus): Middleware {
    override fun <T : Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        eventBus.preRequest()
        val response = next(request)
        eventBus.postRequest()
        return response
    }
}
