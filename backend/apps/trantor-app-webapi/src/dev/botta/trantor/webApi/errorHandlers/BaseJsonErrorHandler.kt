package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.web.server.HttpErrorHandler
import dev.botta.trantor.webApi.jsonError
import io.javalin.http.Context
import org.slf4j.Logger

abstract class BaseJsonErrorHandler<T: Exception>: HttpErrorHandler<T> {
    open val status: Int = 400

    override fun handle(error: T, ctx: Context, logger: Logger) {
        ctx.status(status)
        logger.info(error.message, error)
        ctx.jsonError(error)
    }
}
