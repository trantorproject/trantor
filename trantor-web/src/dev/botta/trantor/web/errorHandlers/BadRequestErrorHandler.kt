package dev.botta.trantor.web.errorHandlers

import dev.botta.trantor.web.application.WebApplication
import kotlin.reflect.KClass

class BadRequestErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 400

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApplication.addBadRequestError() {
    addErrorHandler(BadRequestErrorHandler(T::class))
}
