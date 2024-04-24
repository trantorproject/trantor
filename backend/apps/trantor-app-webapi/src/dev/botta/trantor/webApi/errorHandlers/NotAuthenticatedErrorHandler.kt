package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.WebApi
import kotlin.reflect.KClass

class NotAuthenticatedErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 401

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApi.addNotAuthenticatedError() {
    addErrorHandler(NotAuthenticatedErrorHandler(T::class))
}
