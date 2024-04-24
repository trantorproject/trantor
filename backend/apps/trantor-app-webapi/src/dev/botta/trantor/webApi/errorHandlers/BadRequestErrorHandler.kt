package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.WebApi
import kotlin.reflect.KClass

class BadRequestErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 400

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApi.addBadRequestError() {
    addErrorHandler(BadRequestErrorHandler(T::class))
}
