package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.web.server.HttpErrorHandler
import dev.botta.trantor.webApi.respondJsonError
import io.ktor.http.*
import io.ktor.server.application.*
import org.slf4j.Logger

abstract class BaseJsonErrorHandler<T: Throwable>: HttpErrorHandler<T> {
    open val status: HttpStatusCode = HttpStatusCode.BadRequest

    override suspend fun handle(call: ApplicationCall, cause: T, logger: Logger) {
//        ctx.status(status)
        logger.info(cause.message, cause)
        call.respondJsonError(cause, status = status)
    }
}
