package dev.botta.trantor.web.application.routes

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.application.ApplicationExecutor
import dev.botta.trantor.core.auth.CurrentIdentity
import dev.botta.trantor.core.auth.SystemIdentity
import io.javalin.http.Context

interface WebApplicationExecutor: ApplicationExecutor {
    fun <T: Request<R>, R> execute(request: T, context: Context, executionContext: ExecutionContext = ExecutionContext()): R
}

fun <T: Request<R>, R> WebApplicationExecutor.executeAsSystem(request: T, context: Context, executionContext: ExecutionContext = ExecutionContext()): R {
    return execute(request, context, executionContext.withIdentity(SystemIdentity()))
}

/**
 * Who is asking in the HTTP call [context], as the middlewares of the application tell it: the identity a use case run
 * with that call would have. Anonymous when nobody says.
 */
fun WebApplicationExecutor.identityOf(context: Context, executionContext: ExecutionContext = ExecutionContext()) =
    execute(CurrentIdentity, context, executionContext)
