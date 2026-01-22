package dev.botta.trantor.app.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.logging.getLogger

class LoggingMiddleware: Middleware {
    private val logger = getLogger()

    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        logger.info("Executing use case $request" )
        return next(request)
    }
}
