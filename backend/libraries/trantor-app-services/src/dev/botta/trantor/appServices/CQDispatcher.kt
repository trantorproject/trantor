package dev.botta.trantor.appServices

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.Event

interface CQDispatcher {
    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext = ExecutionContext()): R
    fun notify(event: Event)
}
