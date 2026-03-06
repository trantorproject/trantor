package dev.botta.trantor.web.application.routes

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.application.ApplicationExecutor
import dev.botta.trantor.core.auth.SystemIdentity
import io.javalin.http.Context

interface WebApplicationExecutor: ApplicationExecutor {
    fun <T: Request<R>, R> execute(request: T, context: Context, executionContext: ExecutionContext = ExecutionContext()): R
}

fun <T: Request<R>, R> WebApplicationExecutor.executeAsSystem(request: T, context: Context, executionContext: ExecutionContext = ExecutionContext()): R {
    return execute(request, context, executionContext.withIdentity(SystemIdentity()))
}
