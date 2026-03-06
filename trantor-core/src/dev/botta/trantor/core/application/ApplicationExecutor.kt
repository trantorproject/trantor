package dev.botta.trantor.core.application

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.auth.SystemIdentity

interface ApplicationExecutor {
    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext = ExecutionContext()): R

    fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities = MiddlewarePriorities.Normal)
}

fun <T: Request<R>, R> ApplicationExecutor.executeAsSystem(request: T, context: ExecutionContext = ExecutionContext()): R {
    return execute(request, context.withIdentity(SystemIdentity()))
}
