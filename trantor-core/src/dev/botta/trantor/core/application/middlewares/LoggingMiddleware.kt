package dev.botta.trantor.core.application.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.primitives.logging.getLogger

class LoggingMiddleware: Middleware {
    private val logger = getLogger()

    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        logger.info("Executing use case $request" )
        val response = next(request)
        logger.info("Successfully executed use case $request")
        return response
    }
}
