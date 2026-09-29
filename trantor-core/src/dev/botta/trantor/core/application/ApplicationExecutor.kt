package dev.botta.trantor.core.application

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.auth.CurrentIdentity
import dev.botta.trantor.core.auth.SystemIdentity
import dev.botta.cqbus.identity.Identity

interface ApplicationExecutor {
    fun <T: Request<R>, R> execute(request: T, context: ExecutionContext = ExecutionContext()): R

    fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities = MiddlewarePriorities.Normal)
}

fun <T: Request<R>, R> ApplicationExecutor.executeAsSystem(request: T, context: ExecutionContext = ExecutionContext()): R {
    return execute(request, context.withIdentity(SystemIdentity()))
}

/**
 * Who is asking in [context], as the middlewares of the application tell it: [CurrentIdentity] runs through them, so
 * it is the identity any request with that context would run as. Anonymous when nobody says.
 */
fun ApplicationExecutor.identityOf(context: ExecutionContext = ExecutionContext()): Identity =
    execute(CurrentIdentity, context)
