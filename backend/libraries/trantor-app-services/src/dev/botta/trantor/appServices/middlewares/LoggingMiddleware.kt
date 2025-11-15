package dev.botta.trantor.appServices.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.getLogger

class LoggingMiddleware: Middleware {
    private val logger = getLogger()

    override suspend fun <T: Request<R>, R> execute(request: T, next: suspend (T) -> R, context: ExecutionContext): R {
        logger.info("Executing use case $request" )
        return next(request)
    }
}
