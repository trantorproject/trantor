package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.*
import io.javalin.http.Context
import org.slf4j.Logger
import kotlin.reflect.KClass

class InternalErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 500

    constructor(errorType: KClass<T>): this(errorType.java)

    override fun handle(error: T, ctx: Context, logger: Logger) {
        logger.error("Uncaught exception ${error.javaClass.simpleName}: ${error.message}", error)
        ctx.status(500)
        ctx.jsonError("Exception", "Internal error")
    }
}

inline fun <reified T: Exception> WebApi.addInternalError() {
    addErrorHandler(InternalErrorHandler(T::class))
}
