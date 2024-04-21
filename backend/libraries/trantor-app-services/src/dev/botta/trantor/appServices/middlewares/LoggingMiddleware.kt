package dev.botta.trantor.appServices.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import org.slf4j.LoggerFactory

class LoggingMiddleware: Middleware {
    private val logger = LoggerFactory.getLogger(javaClass.name)

    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        logger.info("Executing use case $request" )
        return next(request)
    }
}
