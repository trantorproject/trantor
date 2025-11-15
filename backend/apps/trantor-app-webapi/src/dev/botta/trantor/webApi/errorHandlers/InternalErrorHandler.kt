package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.*
import io.ktor.http.*
import io.ktor.server.application.*
import org.slf4j.Logger
import kotlin.reflect.KClass

class InternalErrorHandler<T: Exception>(override val errorType: KClass<T>): BaseJsonErrorHandler<T>() {
    override val status = HttpStatusCode.InternalServerError

    override suspend fun handle(call: ApplicationCall, cause: T, logger: Logger) {
        logger.error("Uncaught exception ${cause.javaClass.simpleName}: ${cause.message}", cause)
        call.respondJsonError("Exception", "Internal error", status)
    }
}

inline fun <reified T: Exception> BaseWebApi.addInternalError() {
    addErrorHandler(InternalErrorHandler(T::class))
}
